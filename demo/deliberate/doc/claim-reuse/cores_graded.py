"""Experiment f, step 2: Jev's graded implication belief (implication_belief.score) for each
original claim -> its core, compared with the binary Choice and the Opus adjudication.

Writes data/cores_graded.json: [{"eval": index into cores_eval.json, "w"}]; data/impl_gold.json holds
the adjudication ({"k": index into cores_eval.json, "label": YES/NO, "why"}).
Run: TYPESAFE_API_KEY=... python3 cores_graded.py [--score-only]
"""
import sys
from concurrent.futures import ThreadPoolExecutor
import common
from implication_belief import score

claims = common.load_claims()
cores = common.load("cores.json")
E = common.load("cores_eval.json")
own = [(n, e) for n, e in enumerate(E) if e["kind"] == "own" and cores[e["core"]]["useful"]]

def ask(item):
    n, e = item
    try:
        ans, _ = common.jev({"claim_a": claims[e["other"]]["text"], "claim_b": cores[e["core"]]["core"]},
                            {"ab": score("claim_a", "claim_b")})
        return {"eval": n, "w": ans["ab"]["score"] / 4}
    except Exception as ex:  # noqa: BLE001 - experiment script: record and move on
        return {"eval": n, "error": repr(ex)}

if "--score-only" not in sys.argv:
    with ThreadPoolExecutor(6) as ex:
        common.save(list(ex.map(ask, own)), "cores_graded.json")

W = {r["eval"]: r["w"] for r in common.load("cores_graded.json")}
gold = {g["k"]: g["label"] == "YES" for g in common.load("impl_gold.json")}
assert set(W) == set(gold) == {n for n, _ in own}
yes = {n for n in gold if gold[n]}
print(f"claim -> core implications: {len(gold)}; Opus adjudication YES {len(yes)} ({len(yes) / len(gold):.0%})")
def pr(name, pred):
    tp = len(pred & yes)
    print(f"  {name:28} precision {tp}/{len(pred)} = {tp / max(1, len(pred)):.2f}   recall {tp}/{len(yes)} = {tp / len(yes):.2f}")
pr("Jev binary Choice", {n for n, e in own if e["other_implies_core"]})
for t in (.5, .75, 1.0):
    pr(f"Jev graded w >= {t}", {n for n in W if W[n] >= t})
by_core = {}
for n, e in own:
    by_core.setdefault(e["core"], []).append(gold[n])
ok = [c for c, v in by_core.items() if all(v)]
print(f"cores valid by adjudication (both originals imply it): {len(ok)}/{len(by_core)} useful cores "
      f"({len(ok)}/{len(cores)} of all near-miss pairs)")
