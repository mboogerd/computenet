import json
from lib import *
G = S / "verify/gold/ratings"
prior = load_prior(); g = load_gold("majority")
rat = {}
for q in g["ts"]["questions"]:
    for m in ("opus", "sol"):
        for o in ("fwd", "rev"):
            j = json.loads((G / f"{m}-{q['id']}-{o}.json").read_text())
            for cl, kv in j["ratings"].items():
                for k, v in kv.items(): rat.setdefault((cl, k), []).append(v)
orig = load_gold("original")["refs"]
zero = [k for k in g["refs"] if g["refs"][k] == "0" and k not in g["contested"]]
unan = [k for k in zero if all(v == "0" for v in rat[k]) and orig[k] == "0"]
print(f"neutral cells: {len(zero)}, unanimous-0 (4 LLM ratings + original): {len(unan)}")
base = [PILOT / "responses.jsonl"]; B = ["responses_b.jsonl"]
sets = [("ISO", base, s) for s in ("compat", "elim", "lik", "bear")] + [("B", B, s) for s in ("delta", "pin", "instr", "instrx", "cinstr")]
def r_on(sig, ks):
    return pearson([prior[(g["q_of"][k[0]], k[1])] for k in ks], [sig[k] for k in ks])
def within(sig, ks):  # prior and signal demeaned within question
    qs = {g["q_of"][k[0]] for k in ks}; px, py = [], []
    for q in qs:
        kk = [k for k in ks if g["q_of"][k[0]] == q]
        mp = mean(prior[(q, k[1])] for k in kk); ms = mean(sig[k] for k in kk)
        px += [prior[(q, k[1])] - mp for k in kk]; py += [sig[k] - ms for k in kk]
    return pearson(px, py)
print(f"{'shape':7} {'all0':>5} {'unan0':>6} {'within-q':>8} {'noQ4':>5} | per-question r")
for arm, f, s in sets:
    v = collect(f, arm, [s])[s]
    noq4 = [k for k in zero if g["q_of"][k[0]] != "Q4"]
    perq = {q: r_on(v, [k for k in zero if g["q_of"][k[0]] == q]) for q in ("Q1", "Q2", "Q3", "Q4")}
    print(f"{s:7} {r_on(v, zero):5.2f} {r_on(v, unan):6.2f} {within(v, zero):8.2f} {r_on(v, noq4):5.2f} | " + " ".join(f"{q}:{x:5.2f}" for q, x in perq.items()))
print("\nplacebo mean value per class vs prior (compat, elim-as-1-noul, bear, delta):")
plc = collect(B, "PLC", ["compat", "elim", "bear", "delta", "lik"])
acc = {}
for s, d in plc.items():
    for (qp, c), x in d.items(): acc.setdefault((qp.split("/")[0], c), {}).setdefault(s, []).append(x)
for k in sorted(acc):
    print(k, f"prior {prior[k]:.2f}", {s: round(mean(x), 2) for s, x in acc[k].items()})
