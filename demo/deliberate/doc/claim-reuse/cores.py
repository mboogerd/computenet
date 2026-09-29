# /// script
# dependencies = ["sentence-transformers", "numpy"]
# ///
"""Experiment f: common cores of near-miss pairs.

data/cores.json holds, per gold OVERLAP pair at cos >= .85, an extracted core (Opus; prompt in
README) with each claim's residual detail and a `useful` flag. This script asks Jev (jev_entail's
directional Choice):
  1. validity   does each original claim imply its core?
  2. reuse      for each core, its corpus neighbours (cos >= .80, top 8) and the other cores
                (cos >= .85): which claims imply the core, and which are the same as it
                (mutual implication)?
Writes data/cores_eval.json. `--graded` then asks Jev's graded belief (implication_belief.score) for every
entry into data/cores_eval_graded.json; the summary uses graded w >= .5, which recovers far more true
implications than the binary Choice (cores_graded.py). Run: uv run cores.py; uv run cores.py --graded;
uv run cores.py --score-only
"""
import sys
from concurrent.futures import ThreadPoolExecutor
import numpy as np
import common
from jev_entail import implies

def entail(a, b):
    try:
        ans, _ = common.jev({"claim_a": a, "claim_b": b}, {"ab": implies("claim_a", "claim_b"), "ba": implies("claim_b", "claim_a")})
        return ans["ab"]["choice"] == "YES", ans["ba"]["choice"] == "YES"
    except Exception as e:  # noqa: BLE001 - experiment script: record and move on
        return repr(e)

claims = common.load_claims()
pairs = {p["pair"]: p for p in common.expand(common.load("sample.json"), claims)}
cores = common.load("cores.json")

if not {"--score-only", "--graded"} & set(sys.argv):
    from sentence_transformers import SentenceTransformer
    m = SentenceTransformer("BAAI/bge-small-en-v1.5")
    Ec = m.encode([c["text"] for c in claims], normalize_embeddings=True, batch_size=64)
    Ek = m.encode([k["core"] for k in cores], normalize_embeddings=True, batch_size=64)
    Sc, Sk = Ek @ Ec.T, Ek @ Ek.T
    jobs = []  # (core index, kind, other id, text)
    for i, k in enumerate(cores):
        p = pairs[k["pair"]]
        jobs += [(i, "own", p["a"], p["A"]["claim"]), (i, "own", p["b"], p["B"]["claim"])]
        for j in np.argsort(-Sc[i])[:8]:
            if Sc[i, j] >= .80 and j not in (p["a"], p["b"]):
                jobs.append((i, "corpus", int(j), claims[j]["text"]))
        for j in np.argsort(-Sk[i]):
            if j != i and Sk[i, j] >= .85 and i < j:
                jobs.append((i, "core", int(j), cores[j]["core"]))
    with ThreadPoolExecutor(6) as ex:
        res = list(ex.map(lambda jb: entail(jb[3], cores[jb[0]]["core"]), jobs))  # a = other, b = core
    common.save([{"core": i, "kind": kind, "other": o, "other_implies_core": r[0] if isinstance(r, tuple) else None,
                  "core_implies_other": r[1] if isinstance(r, tuple) else None, "error": None if isinstance(r, tuple) else r}
                 for (i, kind, o, _), r in zip(jobs, res)], "cores_eval.json")

E = common.load("cores_eval.json")
if "--graded" in sys.argv and "--score-only" not in sys.argv:
    from implication_belief import score
    def graded(n):
        e = E[n]
        other = claims[e["other"]]["text"] if e["kind"] != "core" else cores[e["other"]]["core"]
        try:
            ans, _ = common.jev({"claim_a": other, "claim_b": cores[e["core"]]["core"]},
                                {"ab": score("claim_a", "claim_b"), "ba": score("claim_b", "claim_a")})
            return {"eval": n, "w_other_core": ans["ab"]["score"] / 4, "w_core_other": ans["ba"]["score"] / 4}
        except Exception as ex:  # noqa: BLE001
            return {"eval": n, "error": repr(ex)}
    with ThreadPoolExecutor(6) as ex:
        common.save(list(ex.map(graded, range(len(E)))), "cores_eval_graded.json")
GR = {r["eval"]: r for r in common.load("cores_eval_graded.json")}
assert not any("error" in r for r in GR.values()), "rerun --graded: some requests failed"
for n, e in enumerate(E):  # graded belief (w >= .5) replaces the binary Choice: recall .94 vs .54 (cores_graded.py)
    e["other_implies_core"] = GR[n]["w_other_core"] >= .5
    e["core_implies_other"] = GR[n]["w_core_other"] >= .5
assert not any(e["error"] for e in E), "rerun: some requests failed"
useful = [i for i, k in enumerate(cores) if k["useful"]]
own = {}
for e in E:
    if e["kind"] == "own":
        own.setdefault(e["core"], []).append(e["other_implies_core"])
valid = [i for i in useful if all(own[i])]
print(f"pairs: {len(cores)}; extractor says a useful core exists for {len(useful)} ({len(useful) / len(cores):.0%})")
print(f"validity (Jev: both originals imply the core): {len(valid)}/{len(useful)} useful cores; "
      f"one of two {sum(sum(own[i]) == 1 for i in useful)}, neither {sum(sum(own[i]) == 0 for i in useful)}")

qs = lambda i: {pairs[cores[i]['pair']]['A']['question'], pairs[cores[i]['pair']]['B']['question']}
same_existing, implied_by = [], {}
for e in E:
    if e["kind"] == "corpus" and e["core"] in valid:
        if e["other_implies_core"] and e["core_implies_other"]:
            same_existing.append(e["core"])
        if e["other_implies_core"]:
            implied_by.setdefault(e["core"], []).append(e["other"])
print(f"reuse against the rest of the corpus (valid cores, neighbours at cos >= .80):")
print(f"  a claim equivalent to the core already exists: {len(set(same_existing))}/{len(valid)}")
n_extra = [len(implied_by.get(i, [])) for i in valid]
cross = [sum(claims[o]["question"] not in qs(i) for o in implied_by.get(i, [])) for i in valid]
print(f"  further claims implying the core (beyond its own pair): mean {np.mean(n_extra):.2f}, "
      f"cores with >= 1: {sum(x >= 1 for x in n_extra)}, from a question outside the pair's: {sum(x >= 1 for x in cross)}")
cc = [e for e in E if e["kind"] == "core" and e["core"] in valid and e["other"] in valid]
print(f"  core-core near pairs (cos >= .85): {len(cc)}; the same proposition (mutual): "
      f"{sum(e['other_implies_core'] and e['core_implies_other'] for e in cc)}, one implies the other: "
      f"{sum(e['other_implies_core'] != e['core_implies_other'] for e in cc)}")
for i in [i for i in valid if n_extra[valid.index(i)] >= 2][:3]:
    print(f"\n  core: {cores[i]['core']}")
    for o in implied_by[i][:3]:
        print(f"    <= [{claims[o]['question'][:30]}] {claims[o]['text'][:95]}")
