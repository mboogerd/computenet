import json, sys
sys.path.insert(0, '.')
import pilot
from pathlib import Path
_, reqs = pilot.build_plan()
meta = {r["rid"]: r for r in reqs}
ans = {json.loads(l)["rid"]: json.loads(l)["response"]["answers"] for l in open("responses.jsonl")}
def golds(p):
    t = json.load(open(p)); ref, cont = {}, set()
    for q in t["questions"]:
        for c in q["claims"]:
            for k, v in c["ref"].items(): ref[(c["id"], k)] = v
            for k in c.get("contested", {}): cont.add((c["id"], k))
    return ref, cont
G = {"orig": golds("testset.json"), "llm": golds("testset_llm.json")}
for arm in ("ISO", "CTX"):
    for shape in ("bear", "compat", "elim"):
        reps = {}
        for rid, a in ans.items():
            m = meta[rid]
            if m["arm"] != arm: continue
            for k, x in a.items():
                s, _, cid = k.partition("|")
                if s == shape: reps.setdefault((m["cid"], cid), []).append(x)
        pred = {k: pilot.mean(pilot.ruled_out(shape, x) for x in xs) > 0.5 for k, xs in reps.items()}
        for gname, (ref, cont) in G.items():
            keys = [k for k in reps if k not in cont]
            tp = [k for k in keys if pred[k] and ref[k] == "--"]
            fp = [k for k in keys if pred[k] and ref[k] != "--"]
            fn = [k for k in keys if not pred[k] and ref[k] == "--"]
            print(f"{arm} {shape} {gname}: n={len(keys)} TP={len(tp)} FP={len(fp)} {[(k, ref[k]) for k in fp]} FN={len(fn)} {fn}")
        if shape == "bear":
            v = {k: pilot.mean(x['score'] / 4 for x in xs) for k, xs in reps.items()}
            print("  G5 MARINE bear:", {c: round(v[(c, 'MARINE')], 3) for c in ("d1", "d2", "d8")})
