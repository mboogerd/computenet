"""Scores experiment c: self-containment, and plausibility judged without vs with the question."""
import statistics as st
from collections import Counter
import common

claims = common.load_claims()
R = {r["id"]: r for r in common.load("sc.json")}
G = {g["id"]: g for g in common.load("sc_gold.json")}
RW = {r["id"]: r for r in common.load("sc_rewrites_out.json")}
assert not any("error" in r for r in R.values())

print("Jev self-containment over all claims:", dict(Counter(r["sc"] for r in R.values())))
gp = [i for i in G if G[i]["label"] == "NEEDS_CONTEXT"]
jp = [i for i in G if R[i]["sc"] == "NEEDS_CONTEXT"]
tp = set(gp) & set(jp)
print(f"150-claim gold sample: NEEDS_CONTEXT {len(gp)} ({len(gp) / len(G):.0%}), by question "
      f"{dict(Counter(claims[i]['question'] for i in gp))}")
print(f"Jev as a detector on that sample: precision {len(tp)}/{len(jp)}, recall {len(tp)}/{len(gp)}")

def delta(ids):
    x = [abs(R[i]["p_bare"] - R[i]["p_q"]) for i in ids]
    flips = sum((R[i]["p_bare"] - .5) * (R[i]["p_q"] - .5) < 0 for i in ids)
    return f"n={len(x):4}  mean |Δp| {st.mean(x):.3f}  |Δp| >= .25: {sum(v >= .25 for v in x):3}  opposite sides of .5: {flips}"
print("\nplausibility without vs with root_question:")
print("  all claims          ", delta(list(R)))
print("  gold SELF_CONTAINED ", delta([i for i in G if G[i]["label"] == "SELF_CONTAINED"]))
print("  gold NEEDS_CONTEXT  ", delta(gp))
noise = [abs(claims[i]["plausibility"] - R[i]["p_q"]) for i in R if claims[i]["plausibility"] is not None]
print(f"noise floor — snapshot plausibility vs a fresh request with the same prompt: mean |Δp| {st.mean(noise):.3f}")

print("\nrewrites of the gold NEEDS_CONTEXT claims — Jev re-check:", dict(Counter(r["sc"] for r in RW.values())))
for i, r in RW.items():
    print(f"  claim {i}: original, with question {R[i]['p_q']:.2f} / without {R[i]['p_bare']:.2f}; "
          f"rewrite, without question {r['p_bare']:.2f}  [{r['sc']}]")
