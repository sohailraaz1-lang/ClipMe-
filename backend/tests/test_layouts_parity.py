"""Proves the app's live preview (app/src/layouts.ts) computes the same tiles as the renderer."""
import json, os, shutil, subprocess, sys, tempfile, unittest
HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, ".."))
from layouts import PRESETS, Box, LayoutSpec, layout_plan, target_aspects, fit_box_aspect

CASES = []
for pid in PRESETS:
    for ratio in ("50/50", "60/40", "70/30"):
        for corner in ("tl", "br", "bl"):
            for size in ("S", "M", "L"):
                for sa in (16 / 9, 4 / 3, 21 / 9):
                    CASES.append(dict(preset=pid, ratio=ratio, corner=corner, size=size, sa=sa,
                                      game=[0.31, 0.02, 0.4, 0.9], cam=[0.03, 0.55, 0.22, 0.3]))


class Parity(unittest.TestCase):
    @unittest.skipUnless(shutil.which("node"), "node missing")
    def test_ts_matches_python(self):
        exp = []
        for c in CASES:
            s = LayoutSpec(c["preset"], c["ratio"], c["corner"], c["size"], Box(*c["game"]), Box(*c["cam"]))
            plan = layout_plan(s, c["sa"]).to_dict()
            t = target_aspects(s)
            fb = fit_box_aspect(Box(*c["cam"]), t["cam"], c["sa"])
            exp.append({"plan": plan, "t": t, "fit": [fb.x, fb.y, fb.w, fb.h]})
        d = tempfile.mkdtemp()
        runner = os.path.join(d, "r.mts")
        shutil.copy(os.path.join(HERE, "..", "..", "app", "src", "layouts.ts"), os.path.join(d, "layouts.mts"))
        open(runner, "w").write("""
import { layoutPlan, targetAspects, fitBoxAspect } from './layouts.mts';
import fs from 'fs';
const cases = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const out = cases.map((c: any) => {
  const s = { preset: c.preset, splitRatio: c.ratio, camCorner: c.corner, camSize: c.size,
              gameBox: { x: c.game[0], y: c.game[1], w: c.game[2], h: c.game[3] },
              camBox: { x: c.cam[0], y: c.cam[1], w: c.cam[2], h: c.cam[3] } };
  const t = targetAspects(s as any);
  const f = fitBoxAspect(s.camBox, t.cam, c.sa);
  return { plan: layoutPlan(s as any, c.sa), t, fit: [f.x, f.y, f.w, f.h] };
});
console.log(JSON.stringify(out));
""")
        cf = os.path.join(d, "cases.json"); json.dump(CASES, open(cf, "w"))
        r = subprocess.run(["node", "--experimental-strip-types", "--no-warnings", runner, cf], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr[-800:])
        got = json.loads(r.stdout)
        def norm(o):
            if isinstance(o, float): return round(o, 6)
            if isinstance(o, list): return [norm(x) for x in o]
            if isinstance(o, dict): return {k: norm(v) for k, v in o.items()}
            return o
        for i, (a, b) in enumerate(zip(exp, got)):
            self.assertEqual(norm(a), norm(b), f"case {i}: {CASES[i]}")
        self.assertEqual(len(exp), len(got))

if __name__ == "__main__":
    unittest.main()
