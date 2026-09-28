"""Scores the match experiment: embedding recall and Jev precision against data/gold.json."""
from collections import Counter
import common

claims = common.load_claims()
S = {p["pair"]: p for p in common.expand(common.load("sample.json"), claims)}
G = {g["pair"]: g for g in common.load("gold.json")}
J = {v: {int(k): r["label"] for k, r in common.load(f"jev_{v}.json").items()} for v in ("bare", "ctx")}
E = {int(k): v for k, v in common.load("jev_entail.json").items()}
P = common.load("pairs.json")
assert set(G) == set(S), "gold must label every sampled pair"

BANDS = [(.9, 1.01), (.85, .9), (.8, .85), (.75, .8)]
def band_of(p):
    c = S[p]["cos"]
    return "ctl<.70" if c < .7 else next(f"{lo:.2f}+" for lo, hi in BANDS if lo <= c < hi)
pop = {f"{lo:.2f}+": sum(lo <= s < hi for _, _, s in P["topk"]) for lo, hi in BANDS}
pop["ctl<.70"] = P["band_size"]

print("band      n  gold labels                                     SAME%    pop  est.SAME")
est = {}
for b in list(pop):
    ps = [p for p in G if band_of(p) == b]
    same = sum(G[p]["label"] == "SAME" for p in ps)
    est[b] = same / len(ps) * pop[b]
    print(f"{b:8} {len(ps):3}  {str(dict(Counter(G[p]['label'] for p in ps))):46} {same / len(ps):5.0%} {pop[b]:6} {est[b]:8.0f}")
total = sum(est.values())
print(f"estimated SAME pairs among top-3 neighbours + control band: {total:.0f}")

def report(name, pred, tau):
    ps = [p for p in G if band_of(p) != "ctl<.70" and S[p]["cos"] >= tau]
    hit = [p for p in ps if pred(p)]
    tp = [p for p in hit if G[p]["label"] == "SAME"]
    recall = sum(sum(1 for p in tp if band_of(p) == b) / sum(1 for p in G if band_of(p) == b) * pop[b] for b in pop) / total
    print(f"  cos>={tau:.2f}  {name:34} precision {len(tp) / max(1, len(hit)):.2f} ({len(tp)}/{len(hit)})  est. recall {recall:.2f}")

print("\npipeline = cosine threshold (recall stage), then Jev (precision stage):")
for tau in (.85, .9):
    report("4-way Choice, bare texts", lambda p: J["bare"][p] == "SAME", tau)
    report("4-way Choice, + question/parent", lambda p: J["ctx"][p] == "SAME", tau)
    report("mutual implication", lambda p: p in E and E[p]["ab"] == E[p]["ba"] == "YES", tau)
    report("implication either direction", lambda p: p in E and "YES" in (E[p]["ab"], E[p]["ba"]), tau)

labs = ["SAME", "CONTEXT_DEPENDENT", "OVERLAP", "DIFFERENT"]
print("\nconfusion, gold (rows) x Jev ctx (cols):", labs)
for g in labs:
    print(f"  {g:18}", [sum(1 for p in G if G[p]["label"] == g and J["ctx"][p] == j) for j in labs])

cons = {(run, n["text"]): n["consensus"] for run in common.RUNS for n in common.graph(run)["nodes"]
        if n["kind"] == "CLAIM" and "text" in n}
def divergence(ps):
    d = []
    for p in ps:
        a, b = claims[S[p]["a"]], claims[S[p]["b"]]
        ca, cb = cons[(a["run"], a["text"])], cons[(b["run"], b["text"])]
        d.append((abs(a["plausibility"] - b["plausibility"]), abs(ca - cb), (ca - .5) * (cb - .5) < 0))
    return (f"n={len(d):3}  mean |Δplausibility| {sum(x[0] for x in d) / len(d):.3f}  "
            f"mean |Δconsensus| {sum(x[1] for x in d) / len(d):.3f}  opposite verdicts {sum(x[2] for x in d)}")
print("\nhow far separately deliberated twins ended up:")
print("  gold SAME           ", divergence([p for p in G if G[p]["label"] == "SAME"]))
print("  gold OVERLAP >= .85 ", divergence([p for p in G if G[p]["label"] == "OVERLAP" and S[p]["cos"] >= .85]))
print("gold SAME by question pair:",
      Counter((S[p]["A"]["question"], S[p]["B"]["question"]) for p in G if G[p]["label"] == "SAME").most_common())
usage = [r["usage"]["input_tokens"] for r in E.values() if r.get("usage")]
print(f"\nJev input tokens per implication request: {sum(usage) / len(usage):.0f}; "
      f"cos >= .85 candidates per claim: {sum(s >= .85 for _, _, s in P['topk']) / len(claims):.2f}")
