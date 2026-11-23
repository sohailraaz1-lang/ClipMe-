(() => {
  const st = {
    llm: [["qwen25_05b","Qwen 2.5 0.5B (Fast)",491e6],["gemma2_2b","Gemma 2 2B (Recommended)",1.71e9],["llama32_3b","Llama 3.2 3B (High Quality)",2.02e9],["phi3_mini","Phi-3 Mini 4K",2.39e9]].map(a=>({id:a[0],name:a[1],approx:a[2],installed:false,size:0,busy:false})),
    asr: [["vosk_en_in","Vosk Indian English",36e6],["vosk_hi","Vosk Hindi & Regional",42e6],["vosk_en_us","Vosk Global English",40e6]].map(a=>({id:a[0],name:a[1],approx:a[2],installed:false,size:0,busy:false})),
    active: {llm:"",asr:""}, engine: window.__engine !== false
  };
  const timers = {};
  window.__log = {saved:[], shared:null, deleted:null, prompts:[], llmCalls:0, prep:0, fetch:[]};
  const all = () => st.llm.concat(st.asr);
  window.AndroidBridge = {
    models(){ return JSON.stringify(st) },
    mdlGet(id){ const m = all().find(x=>x.id==id); m.busy = true; let d=0; const t=m.approx;
      timers[id] = setInterval(()=>{ d += t/10; window.onMdl(id, Math.min(d,t), t);
        if(d >= t){ clearInterval(timers[id]); m.busy=false; m.installed=true; m.size=t; const k = st.llm.includes(m)?"llm":"asr"; if(!st.active[k]) st.active[k]=id; window.onMdlEnd(id,true,"") } }, 60) },
    mdlCancel(id){ clearInterval(timers[id]); const m=all().find(x=>x.id==id); m.busy=false; setTimeout(()=>window.onMdlEnd(id,false,"Cancelled"),20) },
    mdlUse(id){ const k = st.llm.find(x=>x.id==id)?"llm":"asr"; st.active[k]=id },
    mdlDelete(id){ const m=all().find(x=>x.id==id); m.installed=false; m.size=0; for(const k of ["llm","asr"]) if(st.active[k]==id) st.active[k]="" },
    paste(){ return "https://www.youtube.com/watch?v=abc123" },
    fetch(url, live){ window.__log.fetch.push([url,live]); setTimeout(()=>window.onDlp("Downloading 50%"),30); setTimeout(()=>window.onDl("video.webm",""),150) },
    info(){ return JSON.stringify({title:"Test Stream", duration:70, heatmap:[[0,35,0.2],[35,70,1.0]]}) },
    prep(l){ window.__log.prep++; setTimeout(()=>window.onModel(true),30) },
    capStart(){}, capChunk(b){},
    capEnd(){ return JSON.stringify([["hello",0.3,0.7],["this",0.8,1.1],["is",1.2,1.4],["insane",1.5,2.0],["bro",2.2,2.6],["wait",3.0,3.4]]) },
    face(b){ return "[0.5,0.5,0.2]" },
    llmRun(id, sys, user, max, tok){ window.__log.llmCalls++; window.__log.prompts.push(user);
      if(window.__llmFail) return setTimeout(()=>window.onLlm(tok,false,"Could not load the model (low memory)"),50);
      setTimeout(()=>window.onLlm(tok,true,"2 | 93 | HE DID NOT EXPECT THIS | Loud reaction\n3 | 80 | WAIT FOR IT | Peak audio\n1 | 70 | UNREAL MOMENT | Big energy"),120) },
    save(b64,name,mime){ window.__log.saved.push({name,mime,len:b64.length,b64}); return "content://media/external/downloads/"+window.__log.saved.length },
    share(u){ window.__log.shared=u }, del(u){ window.__log.deleted=u }
  };
})();
