import sys, json
from lib import *
extra = sys.argv[1:]  # optional extra response files (Part B) -> also analyses new shapes
BASE = [PILOT / "responses.jsonl"]
prior = load_prior()
SH = ["compat", "elim", "lik", "bear"]
NEW = ["delta", "pin", "instr", "instrx", "cinstr"]
rows = []
def tie_of(name, kind):
    return 0.02 if kind == "prob" or "linear" in name or name == "raw-centred" else 0.08
for arm, files, shapes in [("ISO", BASE, SH), ("CTX", BASE, SH)] + ([("B", extra, NEW)] if extra else []):
    vals = collect(files, arm, shapes)
    plc = collect(extra, "PLC", SH + NEW) if extra else {}
    # placebo: rid PLC|<qid>/<placebo>|para -> key (qid/placebo, class); average per (qid,class)
    plc_q = {s: {} for s in plc}
    for s, d in plc.items():
        acc = {}
        for (qp, c), v in d.items(): acc.setdefault((qp.split("/")[0], c), []).append(v)
        plc_q[s] = {k: mean(v) for k, v in acc.items()}
    for s in shapes:
        if s not in vals: continue
        for gname in GOLDS:
            g = load_gold(gname)
            for cname, (sig, kind) in corrections(vals[s], g, prior, s, plc_q.get(s) if arm in ("ISO", "B") else None).items():
                rr = (lambda v: v < 0.5) if (kind == "prob" and s in ("compat", "elim", "cinstr")) else None
                m = metrics(sig, g, prior, kind=kind, tie=tie_of(cname, kind), raw_rule=rr, strong=("--",) if s in ("compat", "elim", "cinstr") else ("--", "++"))
                rows.append(dict(arm=arm if arm != "B" else "ISO-new", shape=s, corr=cname, gold=gname, **m))
import lib; print("fitted betas (LOQO) per shape:", {k: sorted(set(v)) for k, v in lib.BETAS.items()})
out = "part_b_metrics.json" if extra else "part_a_metrics.json"
json.dump(rows, open(out, "w"), indent=1)
def ok(r): return abs(r["leak"]) < 0.4 and r["poa"] >= 0.85
print(f"{'arm':7} {'shape':6} {'correction':27} {'gold':8} {'leak':>5} {'90%CI':>13} {'POA':>5} {'AUC':>5} {'kap':>5} {'FMR':>5}")
for r in rows:
    print(f"{r['arm']:7} {r['shape']:6} {r['corr']:27} {r['gold']:8} {r['leak']:5.2f} ({r['leak_ci'][0]:5.2f},{r['leak_ci'][1]:5.2f}) {r['poa']:5.2f} {r['auc']:5.2f} {r['kappa']:5.2f} {r['fmr']:5.2f} {'PASS' if ok(r) else ''}")
