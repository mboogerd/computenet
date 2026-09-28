"""Analyse pop.json."""
import json, math
from lib import spearman, mean, CANDS, PREPOST, MCN

D = json.load(open("pop.json"))
mcq = [r for r, _ in D["mc"]]; halves = [h for _, h in D["mc"]]
preq = D["pre"]
ROWS = CANDS + PREPOST
def q(xs, f): xs = sorted(xs); return xs[int(f * (len(xs) - 1))]

def per_q_spear(qs, a, b, minn=6):
    return mean([spearman([r[a] for r in t], [r[b] for r in t]) for t in qs if len(t) >= minn])
def pooled(qs, a, b):
    xs = [r[a] for t in qs for r in t]; ys = [r[b] for t in qs for r in t]; return spearman(xs, ys)
def regret(qs, a, b):
    out = []
    for t in qs:
        m = max(r[b] for r in t)
        if m <= 1e-9: continue
        pick = max(t, key=lambda r: r[a]); out.append(pick[b] / m)
    return mean(out)

print(f"# voi2 population: MC set {len(mcq)} questions / {sum(map(len, mcq))} nodes; preposterior set {len(preq)} questions / {sum(map(len, preq))} nodes; K 3-8\n")

# noise ceiling for MC references
print("## MC noise ceiling (split-half per-question Spearman, Spearman-Brown corrected)")
for m in MCN:
    sh = mean([spearman([x[m] for x in h[0]], [x[m] for x in h[1]]) for h in halves if len(h[0]) >= 6])
    print(f"- {m}: split-half {sh:.3f} -> full-sample reliability ≈ {2*sh/(1+sh):.3f}")
print("\nMC ref sanity: fraction of nodes with negative expected gain: " + ", ".join(f"{m} {sum(1 for t in mcq for r in t if r[m] < 0)/sum(map(len, mcq)):.2f}" for m in MCN))

for title, qs, refs in (("preposterior references (3000 questions)", preq, PREPOST), ("Monte Carlo to completion (400 questions)", mcq, PREPOST + MCN)):
    print(f"\n## {title}: mean per-question Spearman (questions with >=6 nodes)")
    print("| candidate | " + " | ".join(refs) + " |\n|---|" + "---|" * len(refs))
    for c in ROWS: print(f"| {c} | " + " | ".join(f"{per_q_spear(qs, c, r):.3f}" for r in refs) + " |")
    print(f"\n### {title}: pooled Spearman over all nodes (what a global voi-eps sees)")
    print("| candidate | " + " | ".join(refs) + " |\n|---|" + "---|" * len(refs))
    for c in ROWS: print(f"| {c} | " + " | ".join(f"{pooled(qs, c, r):.3f}" for r in refs) + " |")
    print(f"\n### {title}: reference value captured by the candidate's top pick (ref(pick)/ref(best), mean)")
    print("| candidate | " + " | ".join(refs) + " |\n|---|" + "---|" * len(refs))
    for c in ROWS: print(f"| {c} | " + " | ".join(f"{regret(qs, c, r):.3f}" for r in refs) + " |")

def stops(qs, refs, label):
    allrows = [r for t in qs for r in t]; N = len(allrows)
    frac = sum(1 for r in allrows if r["eM_w"] < .01) / N
    print(f"\n## {label}: per-node premature stops. eM_w<.01 on {frac:.1%} of {N} nodes. A reference calls a node 'material' when it is above that same quantile of the reference ({1-frac:.1%} of nodes material).")
    print("Columns per reference: premature@.01 (cand<.01 but material) / wasted@.01 (cand>=.01 but not material) || premature at matched rate (cand's own eps set so it drops the same node fraction as eM_w@.01).")
    tau = {r: q([x[r] for x in allrows], frac) for r in refs}
    print("reference thresholds: " + ", ".join(f"{r}={tau[r]:.2e}" for r in refs))
    print("| candidate | nodes<.01 | " + " | ".join(refs) + " |\n|---|---|" + "---|" * len(refs))
    for c in ROWS:
        ec = q([x[c] for x in allrows], frac); below = sum(1 for x in allrows if x[c] < .01)
        cells = []
        for r in refs:
            prem = sum(1 for x in allrows if x[c] < .01 and x[r] > tau[r]); waste = sum(1 for x in allrows if x[c] >= .01 and x[r] <= tau[r])
            pm = sum(1 for x in allrows if x[c] < ec and x[r] > tau[r])
            cells.append(f"{prem}/{waste} ‖ {pm}")
        print(f"| {c} | {below} | " + " | ".join(cells) + " |")
    # per question: question stops when every node < eps
    qfrac = sum(1 for t in qs if max(r["eM_w"] for r in t) < .01) / len(qs)
    print(f"\nper question: eM_w@.01 stops {qfrac:.1%} of questions. premature = stops while the reference's best node is above the reference's {qfrac:.1%} question-quantile; format premature@.01 (stops@.01) ‖ premature at matched stop rate")
    qt = {r: q([max(x[r] for x in t) for t in qs], qfrac) for r in refs}
    print("| candidate | " + " | ".join(refs) + " |\n|---|" + "---|" * len(refs))
    for c in ROWS:
        ec = q([max(x[c] for x in t) for t in qs], qfrac); cells = []
        st = sum(1 for t in qs if max(x[c] for x in t) < .01)
        for r in refs:
            prem = sum(1 for t in qs if max(x[c] for x in t) < .01 and max(x[r] for x in t) > qt[r])
            pm = sum(1 for t in qs if max(x[c] for x in t) < ec and max(x[r] for x in t) > qt[r])
            cells.append(f"{prem} ({st}) ‖ {pm}")
        print(f"| {c} | " + " | ".join(cells) + " |")

stops(preq, PREPOST, "preposterior set")
stops(mcq, PREPOST + MCN, "MC set")

# stratified: per-question Spearman vs MC_log and EVSI_log by K and by leader strength
print("\n## stratified per-question Spearman (MC set): vs MC_log / vs MC_01")
print("| stratum | n | " + " | ".join(ROWS) + " |\n|---|---|" + "---|" * len(ROWS))
for nm, f in (("K 3-4", lambda t: t[0]["K"] <= 4), ("K 5-8", lambda t: t[0]["K"] >= 5), ("s_lead<.5", lambda t: t[0]["slead"] < .5), ("s_lead>=.5", lambda t: t[0]["slead"] >= .5)):
    sub = [t for t in mcq if f(t)]
    print(f"| {nm} | {len(sub)} | " + " | ".join(f"{per_q_spear(sub, c, 'MC_log'):.2f}/{per_q_spear(sub, c, 'MC_01'):.2f}" for c in ROWS) + " |")
