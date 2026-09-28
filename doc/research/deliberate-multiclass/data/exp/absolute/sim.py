#!/usr/bin/env python3
"""Plug Jev's measured absoluteness judgments into exp/none/revise2.py's Hmin-pen simulation.
Judges: oracle; parametric J2(bu=mean on comparative gold, bd=1-mean on absolute gold, sd); empirical resampling
of Jev's per-item judged values by gold class; and a leave-one-question-out (LOQO) linear rescale of those values."""
import sys, io, json, math, random, contextlib, statistics as st
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "none"))
with contextlib.redirect_stdout(io.StringIO()):
    import revise2 as R2          # installs Hmin-pen into revise.PL_layer; prints its own tables (suppressed)
import revise as R
from items import items

M = json.loads((HERE / "metrics.json").read_text())
IT = {x["id"]: x for x in items()}; gold = M["gold"]; jev = M["jev"]

class JEmp(R.J2):
    def __init__(self, comp, ab, seed=5):
        super().__init__(0, 0, 0, seed); self.c, self.a = comp, ab
    def absolute(self, x):
        return self.r.choice(self.a if x["kind"] == "neg" else self.c)

def loqo(shape):
    """Rescale j -> clip((j - lo)/(hi - lo)) with lo/hi = mean judged on comparative/absolute gold of the OTHER questions."""
    comp, ab = [], []
    for q in {IT[i]["qid"] for i in gold}:
        tr = [i for i in gold if IT[i]["qid"] != q]; te = [i for i in gold if IT[i]["qid"] == q]
        lo = st.mean(jev[i][shape] for i in tr if gold[i] == "COMPARATIVE"); hi = st.mean(jev[i][shape] for i in tr if gold[i] == "ABSOLUTE")
        for i in te:
            v = min(1, max(0, (jev[i][shape] - lo) / (hi - lo)))
            (comp if gold[i] == "COMPARATIVE" else ab if gold[i] == "ABSOLUTE" else []).append(v)
    return comp, ab

def run(j, n=1500):
    out = []
    for kind, seed in (("routine", 21), ("contam", 23), ("elim", 22)):
        rnd = random.Random(seed); ds = []; ps = []
        for _ in range(n):
            sc = R2.pop(kind, rnd); d = R.dist("Hmin-pen", sc, j); ds.append(d[R.OM] - (1 - sc["bL"])); ps.append(d[R.OM])
        ds.sort()
        out.append(dict(mean=st.mean(ds), p95=ds[int(.95 * n)], max=ds[-1], pOm=st.mean(ps), frac05=sum(x > .05 for x in ds) / n))
    return out

SCN = ["S1 routine two-sided, K=2 (A vs B, none possible)", "S1c contrastive debate, K=3 (X>Y, Y>Z, Z>X)",
       "Sol-cx: 10 strong contrasts each way + weightless abs objections", "S3 all eliminated (absolute objection to each, e=.72)",
       "S5 Late Bronze Age collapse, listed debate only"]
judges = [("oracle (revise2 up0)", R.J2(sd=.1, seed=5)), ("revise2 up.3 (the feared case)", R.J2(bu=.3, sd=.1, seed=5))]
for s in ("noul|A", "noul|B", "score|A", "score|B", "noul", "score", "all"):
    m = M["metrics"][s]; sd = (m["sd_c"] + m["sd_a"]) / 2
    judges.append((f"Jev {s} parametric bu={m['bias_up']:.2f} bd={m['bias_down']:.2f} sd={sd:.2f}", R.J2(m["bias_up"], m["bias_down"], sd, seed=5)))
    judges.append((f"Jev {s} empirical", JEmp(M["comp_vals"][s], M["abs_vals"][s])))
for s in ("noul", "score", "all"):
    c, a = loqo(s)
    judges.append((f"Jev {s} LOQO-rescaled empirical (comp mean {st.mean(c):.2f}, abs mean {st.mean(a):.2f})", JEmp(c, a)))
# hard threshold at .5 (the kappa operating point): judged absolute = 1{j > .5}
for s in ("score", "all"):
    judges.append((f"Jev {s} thresholded at .5", JEmp([float(v > .5) for v in M["comp_vals"][s]], [float(v > .5) for v in M["abs_vals"][s]])))

print("| judge | routine ΔΩ mean / p95 / max / frac>.05 | +weak objections to every class mean / max | all-elim ΔΩ mean / P(Ω) |")
print("|---|---|---|---|")
res = {}
for name, j in judges:
    o = run(j); res[name] = o
    print(f"| {name} | {o[0]['mean']:+.3f} / {o[0]['p95']:+.3f} / {o[0]['max']:+.3f} / {o[0]['frac05']:.1%} | {o[1]['mean']:+.3f} / {o[1]['max']:+.3f} | {o[2]['mean']:+.3f} / {o[2]['pOm']:.3f} |", flush=True)
print("\n| judge | " + " | ".join(s.split(" (")[0].split(":")[0] for s in SCN) + " |\n|---|" + "---|" * len(SCN))
for name, j in judges:
    print(f"| {name} | " + " | ".join(f"{R.dist('Hmin-pen', R.X[s], j)[R.OM]:.3f}" for s in SCN) + " |")
print("(Ω0: S1 .05, S1c .05, Sol-cx .05, S3 .10, S5 .40; A0 = Ω0 exactly on every row)")
json.dump(res, (HERE / "sim.json").open("w"), indent=1)
