#!/usr/bin/env python3
"""Gold construction + Jev metrics. Writes metrics.json and prints tables."""
import json, random, statistics as st
from pathlib import Path
from itertools import combinations
from items import items

HERE = Path(__file__).resolve().parent
IT = {x["id"]: x for x in items()}
R = {}
for f in (HERE / "ratings").glob("*.json"):
    m, q, o = f.stem.split("-"); R[(m, o)] = {**R.get((m, o), {}), **json.loads(f.read_text())}

def lab(m, i):
    a, b = R[(m, "fwd")][i]["label"], R[(m, "rev")][i]["label"]
    return a if a == b else "UNSTABLE"
gold = {}
for i in IT:
    lo, ls = lab("opus", i), lab("sol", i)
    IT[i]["opus"], IT[i]["sol"] = lo, ls
    IT[i]["rabs"] = st.mean(float(R[k][i]["abs"]) / 100 for k in R)
    if lo == ls and lo != "UNSTABLE": gold[i] = lo

def kappa(a, b, cats):
    n = len(a); po = sum(x == y for x, y in zip(a, b)) / n
    pe = sum((a.count(c) / n) * (b.count(c) / n) for c in cats)
    return (po - pe) / (1 - pe) if pe < 1 else 1.0

print("## Rater agreement")
for m in ("opus", "sol"):
    print(f"{m}: order-stable {sum(IT[i][m] != 'UNSTABLE' for i in IT)}/{len(IT)}; "
          f"order kappa {kappa([R[(m,'fwd')][i]['label'] for i in IT], [R[(m,'rev')][i]['label'] for i in IT], ['ABSOLUTE','COMPARATIVE','MIXED']):.2f}")
cats = ["ABSOLUTE", "COMPARATIVE", "MIXED"]
both = [i for i in IT if IT[i]["opus"] != "UNSTABLE" and IT[i]["sol"] != "UNSTABLE"]
print(f"opus vs sol (both stable, n={len(both)}): agree {sum(IT[i]['opus']==IT[i]['sol'] for i in both)}, "
      f"kappa {kappa([IT[i]['opus'] for i in both], [IT[i]['sol'] for i in both], cats):.2f}")
from collections import Counter
print("gold:", len(gold), Counter(gold.values()))
imap = {"A": "ABSOLUTE", "C": "COMPARATIVE", "M": "MIXED"}
print("author intent vs gold:", Counter((IT[i]["intent"], gold[i][:3]) for i in gold))
print("dropped:", [(i, IT[i]["intent"], IT[i]["opus"][:3], IT[i]["sol"][:3]) for i in IT if i not in gold])

# ---------------------------------------------------------------- Jev
J = {}
for l in (HERE / "jev_responses.jsonl").open():
    r = json.loads(l); a = r["response"]["answers"]
    J[r["rid"]] = {k: (v["noul"] if v["type"] == "noul" else v["score"] / 4) for k, v in a.items()}
    J[r["rid"]]["conf"] = {k: v.get("confidence") for k, v in a.items()}
SH = ["noul|A", "noul|B", "score|A", "score|B"]
for i in J:
    J[i]["noul"] = (J[i]["noul|A"] + J[i]["noul|B"]) / 2
    J[i]["score"] = (J[i]["score|A"] + J[i]["score|B"]) / 2
    J[i]["all"] = (J[i]["noul"] + J[i]["score"]) / 2
SH += ["noul", "score", "all"]

def auc(pos, neg):
    return sum((p > n) + .5 * (p == n) for p in pos for n in neg) / (len(pos) * len(neg))
AB = [i for i in gold if gold[i] == "ABSOLUTE"]; CO = [i for i in gold if gold[i] == "COMPARATIVE"]; MX = [i for i in gold if gold[i] == "MIXED"]

def metrics(s, ab, co):
    pa = [J[i][s] for i in ab]; pc = [J[i][s] for i in co]
    ys = [1] * len(ab) + [0] * len(co); ps = pa + pc
    k = kappa([p > .5 for p in ps], [bool(y) for y in ys], [True, False])
    return dict(auc=auc(pa, pc), kappa=k, bias_up=st.mean(pc), bias_down=1 - st.mean(pa),
                sd_c=st.pstdev(pc), sd_a=st.pstdev(pa), brier=st.mean((p - y) ** 2 for p, y in zip(ps, ys)))

def boot(s, key, n=2000):
    rnd = random.Random(1); vals = []
    for _ in range(n):
        ab = [rnd.choice(AB) for _ in AB]; co = [rnd.choice(CO) for _ in CO]
        vals.append(metrics(s, ab, co)[key])
    vals.sort(); return vals[int(.025 * n)], vals[int(.975 * n)]

print(f"\n## Jev vs gold (n abs={len(AB)}, comp={len(CO)}, mixed={len(MX)})")
print("| shape | AUC [95%] | kappa@.5 | mean judged on COMP (bias up) [95%] | 1-mean on ABS (bias down) | sd comp/abs | Brier | mean on MIXED | r vs rater abs (all 71) |")
print("|---|---|---|---|---|---|---|---|---|")
out = {}
for s in SH:
    m = metrics(s, AB, CO); a_ci = boot(s, "auc"); b_ci = boot(s, "bias_up")
    xs = [J[i][s] for i in IT]; ys = [IT[i]["rabs"] for i in IT]
    r = st.correlation(xs, ys)
    m.update(auc_ci=a_ci, bias_up_ci=b_ci, mixed=st.mean(J[i][s] for i in MX) if MX else None, r_rater=r); out[s] = m
    print(f"| {s} | {m['auc']:.2f} [{a_ci[0]:.2f},{a_ci[1]:.2f}] | {m['kappa']:.2f} | {m['bias_up']:.2f} [{b_ci[0]:.2f},{b_ci[1]:.2f}] | "
          f"{m['bias_down']:.2f} | {m['sd_c']:.2f}/{m['sd_a']:.2f} | {m['brier']:.3f} | {m['mixed'] if m['mixed'] is None else round(m['mixed'],2)} | {r:.2f} |")

print("\n## paraphrase agreement (Pearson over 71 items)")
for a, b in (("noul|A", "noul|B"), ("score|A", "score|B"), ("noul", "score")):
    print(a, b, round(st.correlation([J[i][a] for i in IT], [J[i][b] for i in IT]), 2))

print("\n## Calibration (shape 'all' and 'noul'): judged-absoluteness bin -> fraction ABSOLUTE among abs+comp gold")
for s in ("noul", "score", "all"):
    rows = []
    for lo, hi in ((0, .2), (.2, .4), (.4, .6), (.6, .8), (.8, 1.01)):
        b = [i for i in AB + CO if lo <= J[i][s] < hi]
        if b: rows.append(f"[{lo:.1f},{hi:.1f}) n={len(b)} mean={st.mean(J[i][s] for i in b):.2f} frac_abs={sum(gold[i]=='ABSOLUTE' for i in b)/len(b):.2f}")
    print(s, "; ".join(rows))

print("\n## Comparative-gold items Jev scored most absolute (shape all)")
for i in sorted(CO, key=lambda i: -J[i]["all"])[:8]:
    print(f"  {i} {J[i]['all']:.2f} (noul {J[i]['noul']:.2f} score {J[i]['score']:.2f}) vs {IT[i]['cls']}: {IT[i]['claim']}")
print("## Absolute-gold items Jev scored least absolute")
for i in sorted(AB, key=lambda i: J[i]["all"])[:6]:
    print(f"  {i} {J[i]['all']:.2f} (noul {J[i]['noul']:.2f} score {J[i]['score']:.2f}) vs {IT[i]['cls']}: {IT[i]['claim']}")
# subtype: comparative items that are pure rival-support (no mention of the objected answer) vs explicit comparisons
json.dump(dict(metrics=out, gold=gold, jev={i: {s: J[i][s] for s in SH} for i in J},
               comp_vals={s: [J[i][s] for i in CO] for s in SH}, abs_vals={s: [J[i][s] for i in AB] for s in SH}),
          (HERE / "metrics.json").open("w"), indent=1)
