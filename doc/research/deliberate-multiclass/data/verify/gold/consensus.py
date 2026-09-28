#!/usr/bin/env python3
"""Step 2: agreement stats + consensus gold -> pilot/testset_llm.json."""
import copy, json, statistics
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
PILOT = HERE.parent.parent / "pilot"
TS = json.loads((PILOT / "testset.json").read_text())
R = {"--": 0, "-": 1, "0": 2, "+": 3, "++": 4}
INV = {v: k for k, v in R.items()}
sign = lambda c: (R[c] > 2) - (R[c] < 2)

cells = [(q["id"], c["id"], k) for q in TS["questions"] for c in q["claims"] for k in q["classes"]]
orig = {(q["id"], c["id"], k): c["ref"][k] for q in TS["questions"] for c in q["claims"] for k in q["classes"]}
orig_cont = {(q["id"], c["id"], k) for q in TS["questions"] for c in q["claims"] for k in c.get("contested", {})}
rat, flags = {}, {}
for m in ("opus", "sol"):
    for o in ("fwd", "rev"):
        d = {}
        for q in TS["questions"]:
            j = json.loads((HERE / "ratings" / f"{m}-{q['id']}-{o}.json").read_text())
            for c in q["claims"]:
                for k in q["classes"]:
                    d[(q["id"], c["id"], k)] = j["ratings"][c["id"]][k]
            for cl, kv in (j.get("contested") or {}).items():
                for k, why in (kv or {}).items():
                    flags.setdefault((q["id"], cl, k), []).append(f"{m}/{o}: {why}")
        rat[(m, o)] = d

def kappa(a, b, cats, w=None):
    n = len(a)
    ca, cb = Counter(a), Counter(b)
    if w is None:
        w = lambda x, y: 0.0 if x == y else 1.0
    do = sum(w(x, y) for x, y in zip(a, b)) / n
    de = sum(ca[x] * cb[y] * w(x, y) for x in cats for y in cats) / n / n
    return 1 - do / de if de else float("nan")

def compare(A, B, keys=cells):
    a = [A[k] for k in keys]; b = [B[k] for k in keys]
    n = len(keys)
    ex = sum(x == y for x, y in zip(a, b)) / n
    sg = sum(sign(x) == sign(y) for x, y in zip(a, b)) / n
    k5 = kappa(a, b, list(R))
    kw = kappa([R[x] for x in a], [R[y] for y in b], range(5), lambda x, y: (x - y) ** 2 / 16)
    ks = kappa([sign(x) for x in a], [sign(y) for y in b], (-1, 0, 1))
    ro = kappa([x == "--" for x in a], [y == "--" for y in b], (True, False))
    return dict(n=n, exact=round(ex, 3), sign=round(sg, 3), kappa=round(k5, 3), wkappa_quad=round(kw, 3),
                sign_kappa=round(ks, 3), ruleout_kappa=round(ro, 3))

out = {}
lines = []
def row(name, A, B, keys=cells):
    r = compare(A, B, keys); out[name] = r
    lines.append(f"| {name} | {r['n']} | {r['exact']} | {r['sign']} | {r['kappa']} | {r['wkappa_quad']} | {r['sign_kappa']} | {r['ruleout_kappa']} |")
lines.append("| comparison | n | exact | sign | kappa5 | wkappa(quad) | sign-kappa | rule-out kappa |")
lines.append("|---|---|---|---|---|---|---|---|")
for m in ("opus", "sol"):
    for o in ("fwd", "rev"):
        row(f"{m}-{o} vs original", rat[(m, o)], orig)
for o in ("fwd", "rev"):
    row(f"opus-{o} vs sol-{o}", rat[("opus", o)], rat[("sol", o)])
row("opus-fwd vs sol-rev", rat[("opus", "fwd")], rat[("sol", "rev")])
for m in ("opus", "sol"):
    row(f"{m} fwd vs rev (self)", rat[(m, "fwd")], rat[(m, "rev")])

# consensus
gold, cont, status = {}, {}, {}
for k in cells:
    four = [rat[(m, o)][k] for m in ("opus", "sol") for o in ("fwd", "rev")]
    ranks = [R[x] for x in four]
    med = statistics.median(ranks)
    if med != int(med):  # round toward neutral
        med = int(med) + (1 if med < 2 else 0)
    med = int(med)
    agree = len({sign(x) for x in four}) == 1
    if med == 0 and not all(x == "--" for x in four):
        med = 1
    gold[k] = INV[med]
    if not agree:
        cont[k] = "raters disagree on sign: opus " + "/".join(four[:2]) + ", sol " + "/".join(four[2:])
    status[k] = four

ts2 = copy.deepcopy(TS)
ts2["_legend"]["provenance"] = ("Consensus gold from blind ratings by Opus 5.5 and Sol (gpt-5.6-sol), each question rated "
    "twice per rater (class order forward and reversed). Gold when all four ratings agree on sign; level = median, "
    "rounded toward 0; '--' only when all four say '--'. Sign disagreement -> contested.")
for q in ts2["questions"]:
    for c in q["claims"]:
        c["ref"] = {k: gold[(q["id"], c["id"], k)] for k in q["classes"]}
        c["contested"] = {k: cont[(q["id"], c["id"], k)] for k in q["classes"] if (q["id"], c["id"], k) in cont}
        if not c["contested"]:
            del c["contested"]
(PILOT / "testset_llm.json").write_text(json.dumps(ts2, indent=1, ensure_ascii=False))

print("\n".join(lines))
print(f"\ncells {len(cells)}; consensus contested {len(cont)}; original contested {len(orig_cont)}; "
      f"cells rater-flagged contestable (any of 4 runs) {len(flags)}")
lvl_changed = [k for k in cells if gold[k] != orig[k]]
sign_changed = [k for k in cells if sign(gold[k]) != sign(orig[k])]
ro_changed = [k for k in cells if (gold[k] == "--") != (orig[k] == "--")]
cont_new = [k for k in cells if (k in cont) and k not in orig_cont]
cont_gone = [k for k in cells if (k in orig_cont) and k not in cont]
print(f"level changed {len(lvl_changed)}; sign changed {len(sign_changed)}; rule-out status changed {len(ro_changed)}; "
      f"newly contested {len(cont_new)}; no longer contested {len(cont_gone)}")
print("rule-out cells original:", sum(v == "--" for v in orig.values()), "consensus:", sum(v == "--" for v in gold.values()))
def show(title, ks):
    print(f"\n## {title}")
    for k in ks:
        print(f"{k[0]} {k[1]} {k[2]}: orig {orig[k]}{' (C)' if k in orig_cont else ''} -> gold {gold[k]}{' (C)' if k in cont else ''}  raters {status[k]}")
show("contested (consensus)", [k for k in cells if k in cont])
show("uncontested in consensus but sign/rule-out differs from original", [k for k in cells if k not in cont and (k in sign_changed or k in ro_changed)])
show("level-only changes (uncontested both)", [k for k in lvl_changed if k not in cont and k not in sign_changed and k not in ro_changed])
show("no longer contested", cont_gone)
json.dump({"agreement": out, "flags": {"|".join(k): v for k, v in flags.items()}}, open(HERE / "agreement.json", "w"), indent=1)
