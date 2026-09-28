"""Population study: candidates vs non-circular references (preposterior EVSI + Monte Carlo to completion)."""
import sys, random, math, json
from multiprocessing import Pool
from lib import *

W = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
NQ = int(sys.argv[2]) if len(sys.argv) > 2 else 300
NPRE = int(sys.argv[3]) if len(sys.argv) > 3 else 2000

def one(args):
    seed, mc = args
    rnd = random.Random(seed); cat = gen_cat(rnd, 3, 8)
    nodes = nodes_of(cat); s0 = cat.dist()
    pre = [ends(cat, n) for n in nodes]
    rows = [all_scores(cat, n, s0=s0, s1=a, sz=b) for n, (a, b) in zip(nodes, pre)]
    for r, n in zip(rows, nodes): r["depth"] = n.depth; r["K"] = len(s0); r["slead"] = max(s0.values())
    halves = None
    if mc:
        full, halves = mc_refs(cat, nodes, pre, W, random.Random(seed * 7 + 1))
        for r, f in zip(rows, full): r.update(f)
    return rows, halves

if __name__ == "__main__":
    with Pool(14) as p:
        mcres = p.map(one, [(1000 + i, True) for i in range(NQ)], chunksize=2)
        preres = p.map(one, [(50000 + i, False) for i in range(NPRE)], chunksize=8)
    json.dump({"mc": mcres, "pre": [r for r, _ in preres]}, open("pop.json", "w"))
    print("saved", len(mcres), len(preres), "nodes", sum(len(r) for r, _ in mcres), sum(len(r) for r, _ in preres))
