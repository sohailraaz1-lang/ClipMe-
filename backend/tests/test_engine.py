import json, os, shutil, subprocess, sys, tempfile, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import clip_engine as eng
from layouts import PRESETS, Box, LayoutSpec, layout_plan, target_aspects, fit_box_aspect, W, H

CAM = Box(0.02, 0.65, 0.2, 0.3)
HAVE_FFMPEG = shutil.which("ffmpeg") is not None


def specs():
    for pid in PRESETS:
        for extra in ({}, {"split_ratio": "60/40", "cam_corner": "tl", "cam_size": "S"}, {"split_ratio": "70/30", "cam_corner": "bl", "cam_size": "L"}):
            s = LayoutSpec(preset=pid, cam_box=CAM, **extra)
            t = target_aspects(s)
            s.game_box = fit_box_aspect(Box(0.3, 0, 0.3, 1), t["game"])
            if t["cam"]:
                s.cam_box = fit_box_aspect(CAM, t["cam"])
            yield s


class Geometry(unittest.TestCase):
    def test_all_tiles_inside_canvas_and_boxes_valid(self):
        for s in specs():
            plan = layout_plan(s)
            for t in plan.tiles:
                self.assertTrue(0 <= t.x and 0 <= t.y and t.x + t.w <= W and t.y + t.h <= H, (s.preset, t))
                self.assertEqual((t.w % 2, t.h % 2), (0, 0), (s.preset, t))
                b = t.src
                self.assertTrue(0 <= b.x and b.x + b.w <= 1.0001 and 0 <= b.y and b.y + b.h <= 1.0001)

    def test_filtergraph_all_presets(self):
        for s in specs():
            g = eng.build_filtergraph(s, 30, 20)
            self.assertIn("[outv]", g); self.assertIn("1080", g); self.assertIn("1920", g)
            self.assertNotIn("stretch", g)

    def test_missing_cam_degrades_to_game_only(self):
        s = LayoutSpec(preset="split", cam_box=None)
        self.assertEqual(len(layout_plan(s).tiles), 1)

    def test_legacy_labels(self):
        self.assertEqual(eng.normalize_preset("Circle cam"), "circle_cam")
        self.assertEqual(eng.normalize_preset("Cam + game"), "cam_game")
        with self.assertRaises(ValueError):
            eng.normalize_preset("nope")

    def test_fit_box_aspect_pixel_exact(self):
        for target in (9 / 16, 1.0, 1080 / 960, 1000 / 562):
            b = fit_box_aspect(Box(0.1, 0.1, 0.5, 0.5), target)
            self.assertAlmostEqual((b.w * 16) / (b.h * 9), target, places=3)
            self.assertTrue(b.x >= 0 and b.y >= 0 and b.x + b.w <= 1.0001 and b.y + b.h <= 1.0001)


@unittest.skipUnless(HAVE_FFMPEG, "ffmpeg missing")
class RealRender(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp()
        cls.src = os.path.join(cls.dir, "src.mp4")
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=30:d=2",
                        "-f", "lavfi", "-i", "sine=d=2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-shortest", cls.src], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.dir, ignore_errors=True)

    def test_every_preset_renders_1080x1920(self):
        for pid in PRESETS:
            s = next(x for x in specs() if x.preset == pid)
            req = eng.ClipRequest("u", 0, 2, preset=pid, game_box=s.game_box, cam_box=s.cam_box, hook_text="HOOK", quality="fast")
            out = os.path.join(self.dir, pid + ".mp4")
            eng.render_local(self.src, out, req)
            p = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,codec_name,pix_fmt",
                                "-of", "json", out], capture_output=True, text=True)
            v = json.loads(p.stdout)["streams"][0]
            self.assertEqual((v["width"], v["height"], v["codec_name"], v["pix_fmt"]), (1080, 1920, "h264", "yuv420p"), pid)

    def test_60fps_and_captions_ass(self):
        s = LayoutSpec(preset="game_only")
        req = eng.ClipRequest("u", 0, 2, preset="game_only", fps=60, quality="fast")
        out = os.path.join(self.dir, "f60.mp4")
        eng.render_local(self.src, out, req)
        p = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=r_frame_rate", "-of", "csv=p=0", out], capture_output=True, text=True)
        self.assertEqual(p.stdout.strip(), "60/1")
        ass = os.path.join(self.dir, "c.ass")
        open(ass, "w").write(eng.words_to_ass([{"word": "hello", "start": 0, "end": .5}, {"word": "world", "start": .5, "end": 1}], "box"))
        g = eng.build_filtergraph(s, 30, 2, 16 / 9, captions_ass=ass)
        out2 = os.path.join(self.dir, "cap.mp4")
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", self.src, "-filter_complex", g, "-map", "[outv]", "-t", "1", out2], check=True)


class Parsers(unittest.TestCase):
    def test_gemini_fences_and_wrappers(self):
        raw = 'Here you go:\n```json\n[{"start_sec":"5","end_sec":35,"title":"Hook","reason":"x","viral_score":140}]\n```'
        r = eng.parse_gemini_clip_suggestions(raw)
        self.assertEqual(len(r), 1); self.assertEqual(r[0]["viral_score"], 100)
        self.assertEqual(len(eng.parse_gemini_clip_suggestions('{"clips":[{"start":1,"end":20}]}', offset=100)), 1)
        self.assertEqual(eng.parse_gemini_clip_suggestions('{"clips":[{"start":1,"end":20}]}', offset=100)[0]["start_sec"], 101)

    def test_gemini_garbage_never_raises(self):
        for bad in ("", None, "not json", "[1,2,3]", '[{"start_sec":"a"}]', '[{"start_sec":9,"end_sec":3}]', "```json\n```"):
            self.assertEqual(eng.parse_gemini_clip_suggestions(bad), [])

    def test_gemini_missing_key_and_network(self):
        old = os.environ.pop("GEMINI_API_KEY", None)
        self.assertEqual(eng.gemini_auto_clip("https://youtu.be/x")["error"], "no_api_key")
        import urllib.error
        def boom(*a, **k): raise urllib.error.URLError("down")
        eng.time.sleep = lambda s: None
        self.assertEqual(eng.gemini_auto_clip("https://youtu.be/x", "k", _opener=boom)["error"], "network_or_timeout")
        class R:
            def __enter__(s): return s
            def __exit__(s, *a): pass
            def read(s): return json.dumps({"candidates": [{"content": {"parts": [{"text": "```json\n[{\"start_sec\":10,\"end_sec\":14,\"title\":\"police chase\",\"reason\":\"omg\",\"viral_score\":70}]\n```"}]}}]}).encode()
        r = eng.gemini_auto_clip("https://youtu.be/x", "k", _opener=lambda *a, **k: R())
        self.assertTrue(r["ok"]); c = r["clips"][0]
        self.assertEqual(c["end_sec"] - c["start_sec"], 15)  # stretched to the 15 s minimum
        if old: os.environ["GEMINI_API_KEY"] = old

    def test_hype_spike_5pct(self):
        b = [eng.ChatBucket(100, 399, 8000, []), eng.ChatBucket(200, 400, 8000, ["omg police chase"]), eng.ChatBucket(210, 600, 8000, [])]
        r = eng.detect_chat_hype_spikes(b, 5.0, 12, 25)
        self.assertEqual(len(r), 1)  # 399/8000 = 4.99 % is below 5 %; 200 s and 210 s merge (cooldown)
        self.assertEqual(r[0]["trigger_sec"], 210)  # the stronger spike (7.5 %) wins
        self.assertEqual(r[0]["end_sec"] - r[0]["trigger_sec"], 25); self.assertEqual(r[0]["trigger_sec"] - r[0]["start_sec"], 12)
        self.assertEqual(eng.detect_chat_hype_spikes([eng.ChatBucket(5, 500, 0, [])]), [])
        r2 = eng.detect_chat_hype_spikes([eng.ChatBucket(50, 500, 1000, [])], pre_buffer_sec=99, post_buffer_sec=1)
        self.assertEqual((r2[0]["trigger_sec"] - r2[0]["start_sec"], r2[0]["end_sec"] - r2[0]["trigger_sec"]), (15, 20))

    def test_chat_replay_parse(self):
        ln = json.dumps({"replayChatItemAction": {"videoOffsetTimeMsec": "12000", "actions": [{"addChatItemAction": {"item": {"liveChatTextMessageRenderer": {"message": {"runs": [{"text": "LOL"}]}}}}}]}})
        bk = eng.parse_youtube_live_chat([ln, ln, "garbage"], 10, 100)
        self.assertEqual((len(bk), bk[0].messages_count), (1, 2))

    def test_time_inputs(self):
        P = eng.parse_time_input
        self.assertEqual(P("3000"), 1800); self.assertEqual(P("-3000"), 1800); self.assertEqual(P("12740"), 5260)
        self.assertEqual(P("1:27:40"), 5260); self.assertEqual(P("30 min"), 1800); self.assertEqual(P("1h 20m"), 4800)
        self.assertEqual(P("45"), 45); self.assertIsNone(P("abc")); self.assertIsNone(P(""))
        self.assertEqual(eng.compute_window(10, 5, 9999), (0.0, 300.0))
        self.assertEqual(eng.compute_window(9990, 5, 10000), (9700.0, 10000.0))

    def test_validate(self):
        self.assertIsNone(eng.validate_clip_times(0, 180)); self.assertIsNotNone(eng.validate_clip_times(0, 181))
        self.assertIsNotNone(eng.validate_clip_times(0, 3)); self.assertIsNotNone(eng.validate_clip_times(5, 5))

    def test_ass_words(self):
        a = eng.words_to_ass([{"word": "a", "start": 0, "end": 1}] * 4)
        self.assertEqual(a.count("Dialogue:"), 4)


if __name__ == "__main__":
    unittest.main()
