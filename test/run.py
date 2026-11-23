import sys, json, base64, re, os
from playwright.sync_api import sync_playwright
HTML=open('/home/claude/out/Clipme/app/src/main/assets/index.html','rb').read()
MEDIA='/home/claude/test/media'
MOCK=open('/home/claude/test/mock.js').read()
results=[]
def ok(name,cond,info=""):
    results.append((name,bool(cond),info)); print(("PASS " if cond else "FAIL ")+name+(" -> "+str(info) if info!="" else ""))

def route(r):
    u=r.request.url
    if u.endswith('/index.html') or u.rstrip('/').endswith('clipme.local'):
        return r.fulfill(status=200,content_type='text/html',body=HTML)
    m=re.search(r'/media/([^?]+)',u)
    if m:
        p=os.path.join(MEDIA,m.group(1))
        if not os.path.exists(p): return r.fulfill(status=404,body=b'')
        data=open(p,'rb').read(); ct='video/webm' if p.endswith('webm') else 'audio/wav'
        rng=r.request.headers.get('range')
        if rng:
            mm=re.match(r'bytes=(\d*)-(\d*)',rng); s=int(mm.group(1) or 0); e=int(mm.group(2)) if mm.group(2) else len(data)-1; e=min(e,len(data)-1)
            return r.fulfill(status=206,body=data[s:e+1],headers={'Content-Type':ct,'Content-Range':f'bytes {s}-{e}/{len(data)}','Accept-Ranges':'bytes'})
        return r.fulfill(status=200,body=data,headers={'Content-Type':ct,'Accept-Ranges':'bytes'})
    return r.fulfill(status=404,body=b'')

def newpage(b, engine=True, fail=False):
    ctx=b.new_context(viewport={'width':390,'height':844},device_scale_factor=2)
    pg=ctx.new_page(); errs=[]
    pg.on('pageerror',lambda e:errs.append(str(e)))
    pg.on('console',lambda m: errs.append('console:'+m.text) if m.type=='error' else None)
    pg.route('**/*',route)
    pg.add_init_script(f"window.__engine={'true' if engine else 'false'};window.__llmFail={'true' if fail else 'false'};"+MOCK)
    pg.goto('https://clipme.local/index.html'); pg.wait_for_selector('.cta')
    return pg,errs

with sync_playwright() as p:
    b=p.chromium.launch(args=['--autoplay-policy=no-user-gesture-required','--use-fake-ui-for-media-stream'])
    # ---------------- 1. model screen / downloads ----------------
    pg,errs=newpage(b)
    ok("home loads, no JS errors",not errs,errs)
    ok("no fake 'Active in App' at start", pg.evaluate("act('llm')")is None)
    pg.click('[aria-label="AI models"]')
    ok("4 LLM models listed, all 'Download'", pg.locator('[data-a=mg]').count()==4 and 'Active in App' not in pg.inner_text('#app'))
    pg.screenshot(path='/home/claude/test/s1_models_empty.png')
    pg.click('[data-a=mg][data-id=gemma2_2b]')
    pg.wait_for_timeout(250)
    ok("progress bar + Cancel shown while downloading", pg.locator('.prog').count()>=1 and pg.locator('[data-a=mc]').count()==1, pg.inner_text('#app')[:90].replace('\n',' '))
    pg.screenshot(path='/home/claude/test/s2_downloading.png')
    pg.wait_for_function("act('llm')&&act('llm').id=='gemma2_2b'",timeout=8000)
    ok("after real completion model is Installed + Active", 'Active in App' in pg.inner_text('#app') and 'Installed' in pg.inner_text('#app'))
    pg.click('[data-a=mg][data-id=llama32_3b]'); pg.wait_for_timeout(150); pg.click('[data-a=mc]'); pg.wait_for_timeout(300)
    ok("cancel resets row to Download", pg.locator('[data-a=mg][data-id=llama32_3b]').count()==1 and not pg.evaluate("S.M.llm.find(m=>m.id=='llama32_3b').installed"))
    pg.click('[data-a=mg][data-id=qwen25_05b]'); pg.wait_for_function("S.M.llm.find(m=>m.id=='qwen25_05b').installed",timeout=8000)
    pg.click('[data-a=mu][data-id=qwen25_05b]')
    ok("'Use' switches active model", pg.evaluate("act('llm').id")=='qwen25_05b')
    pg.click('[data-a=mu][data-id=gemma2_2b]')
    pg.click('[data-a=md][data-id=qwen25_05b]')
    ok("delete removes model", not pg.evaluate("S.M.llm.find(m=>m.id=='qwen25_05b').installed"))
    pg.screenshot(path='/home/claude/test/s3_models_done.png')
    # speech tab download
    pg.click('[data-k=mt][data-v="1"]'); pg.click('[data-a=mg][data-id=vosk_en_in]'); pg.wait_for_function("S.M.asr.find(m=>m.id=='vosk_en_in').installed",timeout=8000)
    ok("speech model downloads too", act:=pg.evaluate("act('asr').id")=='vosk_en_in')
    pg.click('[data-a=go][data-v=home]')
    ok("home shows real active LLM name", 'Gemma 2 2B' in pg.inner_text('#app'))
    pg.screenshot(path='/home/claude/test/s4_home.png',full_page=False)

    # ---------------- 2. AI scan -> review -> render ----------------
    pg.evaluate("S.n=2;S.len='20s';S.hook=1;S.ht='';S.rev=1;S.cap=1;S.prm='Find the funniest loudest moments'")
    pg.click('[data-a=paste]'); pg.click('#cta')
    pg.wait_for_selector('text=Review Detected Clips',timeout=60000)
    cl=pg.evaluate("CL")
    ok("LLM was called once with transcripts in prompt", pg.evaluate("__log.llmCalls")==1 and 'insane' in pg.evaluate("__log.prompts[0]"), pg.evaluate("__log.prompts[0]")[:200].replace('\n',' | '))
    ok("download called with the pasted link", pg.evaluate("__log.fetch[0][0]").startswith('https://www.youtube'))
    ok("2 clips returned", len(cl)==2, [(c['s'],c['hook'],c['score']) for c in cl])
    ok("AI hooks used", any(c['hook'] in ('HE DID NOT EXPECT THIS','WAIT FOR IT','UNREAL MOMENT') for c in cl))
    ok("loudest region (40-60s) is among picks", any(38<=c['s']<=42 for c in cl), [c['s'] for c in cl])
    ok("heatmap tag present", any('replayed' in c['tag'] for c in cl), [c['tag'] for c in cl])
    pg.screenshot(path='/home/claude/test/s5_review.png')
    for i in range(2): pg.click(f'[data-a=st][data-i="{i}"][data-v="1"]')
    pg.click('[data-a=render]')
    pg.wait_for_selector('text=Tap to confirm',state='detached',timeout=1000) if False else None
    pg.wait_for_function("S.scr=='det'||S.scr=='home'",timeout=120000)
    out=pg.evaluate("S.out.map(o=>({t:o.type,hook:o.hook,w:o.words.length}))")
    ok("2 clips rendered", len(out)==2, out)
    ok("hook burned = AI hook", all(o['hook'] for o in out))
    ok("captions words present", all(o['w']>0 for o in out))
    pg.screenshot(path='/home/claude/test/s6_clips.png')

    # export a rendered clip to verify real video
    b64=pg.evaluate("fetch(S.out[0].url).then(r=>r.blob()).then(b=>new Promise(r=>{const f=new FileReader();f.onload=()=>r(f.result.split(',')[1]);f.readAsDataURL(b)}))")
    open('/home/claude/test/clip0.webm','wb').write(base64.b64decode(b64))
    # save / share / delete
    pg.click('[data-a=sv][data-i="0"]'); pg.wait_for_timeout(500)
    ok("Save sends file + mime to Android", pg.evaluate("__log.saved.length")==1 and pg.evaluate("__log.saved[0].mime").startswith('video/'), pg.evaluate("__log.saved[0].name"))
    pg.click('[data-a=sh][data-i="0"]'); pg.wait_for_timeout(400)
    ok("Share opens share sheet with saved uri", pg.evaluate("__log.shared")=="content://media/external/downloads/1")
    pg.click('[data-a=sh][data-i="1"]'); pg.wait_for_timeout(800)
    ok("Share on unsaved clip saves first then shares", pg.evaluate("__log.saved.length")==2 and pg.evaluate("__log.shared")=="content://media/external/downloads/2")
    pg.click('[data-a=dx][data-i="1"]'); ok("delete needs 2nd tap", pg.evaluate("S.out.length")==2)
    pg.click('[data-a=dx][data-i="1"]'); ok("delete removes clip + file", pg.evaluate("S.out.length")==1 and pg.evaluate("__log.deleted")=="content://media/external/downloads/2")
    # edit & re-render
    pg.click('[data-a=ed][data-i="0"]'); pg.wait_for_selector('#pv')
    pg.click('[data-a=auto]'); pg.click('[data-a=et][data-v=Text]'); pg.fill('input[data-k=eh]','MY NEW HOOK')
    pg.screenshot(path='/home/claude/test/s7_edit.png')
    pg.click('[data-a=save]'); pg.wait_for_function("S.scr=='det'",timeout=120000)
    ok("edit re-renders clip with new hook", pg.evaluate("S.out[0].hook")=='MY NEW HOOK' and pg.evaluate("S.out.length")==1)
    ok("edit re-render replaced saved file", pg.evaluate("__log.deleted")=="content://media/external/downloads/1")

    # ---------------- 3. AI failure -> graceful fallback ----------------
    pg.close()
    pg,errs=newpage(b,fail=True)
    pg.evaluate("S.M.llm[1].installed=true;S.M.active.llm='gemma2_2b'") if False else None
    pg.click('[aria-label="AI models"]'); pg.click('[data-a=mg][data-id=gemma2_2b]'); pg.wait_for_function("act('llm')",timeout=8000)
    pg.click('[data-a=go][data-v=home]'); pg.evaluate("S.n=1;S.rev=0;S.cap=0;S.hook=0"); pg.click('[data-a=paste]'); pg.click('#cta')
    pg.wait_for_function("S.scr=='det'||S.scr=='home'&&S.out.length",timeout=120000)
    ok("LLM failure -> still makes clips from audio (n=1)", pg.evaluate("S.out.length")==1 and 38<=pg.evaluate("S.out[0].c.s")<=42, pg.evaluate("S.out[0].c.s"))
    ok("no JS errors on failure path", not errs, errs)
    pg.close()
    # ---------------- 4. build without native engine ----------------
    pg,errs=newpage(b,engine=False)
    pg.click('[aria-label="AI models"]')
    ok("no-engine build shows warning on LLM tab", 'no on-device LLM engine' in pg.inner_text('#app'))
    pg.click('[data-a=go][data-v=home]')
    ok("home says engine missing", 'engine missing' in pg.inner_text('#app'))
    pg.evaluate("S.n=1;S.rev=0;S.cap=0"); pg.click('[data-a=paste]'); pg.click('#cta')
    pg.wait_for_function("S.scr=='det'||S.scr=='home'&&S.out.length",timeout=120000)
    ok("no-engine build still clips via audio", pg.evaluate("S.out.length")==1 and pg.evaluate("__log.llmCalls")==0)
    # ---------------- 5. landscape + manual layout + music ----------------
    pg.evaluate("S.out=[];S.fmt='h';S.music=1;S.scr='home'"); pg.evaluate("R()"); pg.click('#cta')
    pg.wait_for_function("S.scr=='det'||S.scr=='home'&&S.out.length",timeout=120000)
    b64=pg.evaluate("fetch(S.out[0].url).then(r=>r.blob()).then(b=>new Promise(r=>{const f=new FileReader();f.onload=()=>r(f.result.split(',')[1]);f.readAsDataURL(b)}))")
    open('/home/claude/test/clip_land.webm','wb').write(base64.b64decode(b64))
    ok("landscape render finished (with music)", pg.evaluate("S.out.length")==1)
    pg.evaluate("S.out=[];S.fmt='v';S.music=0;S.lay='Set it myself';S.corner='bl';S.swap=1;S.scr='home'"); pg.evaluate("R()"); pg.click('#cta')
    pg.wait_for_function("S.scr=='det'||S.scr=='home'&&S.out.length",timeout=120000)
    b64=pg.evaluate("fetch(S.out[0].url).then(r=>r.blob()).then(b=>new Promise(r=>{const f=new FileReader();f.onload=()=>r(f.result.split(',')[1]);f.readAsDataURL(b)}))")
    open('/home/claude/test/clip_manual.webm','wb').write(base64.b64decode(b64))
    ok("manual layout (facecam corner + swap) render finished", pg.evaluate("S.out.length")==1)
    # ---------------- 6. live tab ----------------
    pg.evaluate("S.out=[];S.tab='l';S.url='https://twitch.tv/x';S.src='';S.dur='10s (Short)';S.rev=0;S.scr='home'"); pg.evaluate("R()")
    ok("live tab: no fake chat/fight controls", 'Chat Hype' not in pg.inner_text('#app') and 'Fight' not in pg.inner_text('#app'))
    pg.click('#cta'); pg.wait_for_function("S.scr=='det'||S.scr=='home'&&S.out.length",timeout=120000)
    ok("live tab passes live=1 to downloader & uses 10s length", pg.evaluate("__log.fetch.at(-1)[1]")==1 and pg.evaluate("S.out[0].c.l")==10, pg.evaluate("S.out[0].c.l"))
    ok("face check tagged clips", 'Face' in pg.evaluate("S.out[0].c.tag"), pg.evaluate("S.out[0].c.tag"))
    ok("no JS errors overall (no-engine page)", not errs, errs)

    # ---------------- 7. REAL download failure modes (the "stuck on Downloading 0%" bug) ----------------
    pg,errs=newpage(b); pg.evaluate("window.__fetchMode='fail'")
    pg.click('[data-a=paste]'); pg.click('#cta')
    pg.wait_for_function("!S.busy&&document.querySelector('.toast')",timeout=5000)
    ok("failed download: shows the real reason", 'not a bot' in pg.inner_text('.toast'), pg.inner_text('.toast'))
    ok("failed download: button recovers (not stuck busy)", pg.evaluate("!S.busy&&!S.dl") and 'Downloading' not in pg.inner_text('#cta'), pg.inner_text('#cta'))
    pg.close()
    pg,errs=newpage(b); pg.evaluate("window.__fetchMode='hang'")
    pg.click('[data-a=paste]'); pg.click('#cta')
    pg.wait_for_function("S.busy&&S.busy.includes('40%')",timeout=5000)
    ok("progress text goes in the button, not as spam toasts", 'Downloading 40%' in pg.inner_text('#cta') and 'Downloading 40%' not in pg.inner_text('#t'))
    pg.click('#cta')                       # busy button must still be tappable
    pg.wait_for_function("!S.busy",timeout=5000)
    ok("tapping the busy button cancels a stuck download", pg.evaluate("__log.cancelled")==1)
    ok("cancel is quiet and resets the UI", 'Couldn’t download' not in pg.inner_text('#t') and pg.evaluate("!S.busy&&!S.dl"))
    ok("no JS errors in failure/cancel paths", not errs, errs)
    pg.close()
    b.close()
fails=[r for r in results if not r[1]]
print(f"\n{len(results)-len(fails)}/{len(results)} passed"); sys.exit(1 if fails else 0)
