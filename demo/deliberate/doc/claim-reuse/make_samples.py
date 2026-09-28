"""Draws the labelled samples from data/pairs.json (deterministic seeds).

sample.json    320 pairs for gold labelling: all 100 top-3 pairs at cos >= .90, 60 at .85-.90,
               60 at .80-.85, 40 at .75-.80, and the first 60 control pairs (.55-.70).
all80.json     every top-3 pair at cos >= .80 (experiment b's population).
sc_sample.json 150 random claims for the self-containment gold labels.
Pairs are stored as [pair id, claim a, claim b, cos]; common.expand() restores the texts.
"""
import random
import common

claims = common.load_claims()
P = common.load("pairs.json")
tk = P["topk"]

random.seed(7)
def band(lo, hi, n):
    xs = [p for p in tk if lo <= p[2] < hi]
    random.shuffle(xs)
    return xs[:n]
sample = band(.9, 1.01, 200) + band(.85, .9, 60) + band(.8, .85, 60) + band(.75, .8, 40) + P["control"][:60]
random.shuffle(sample)
rows = [[k, a, b, round(s, 3)] for k, (a, b, s) in enumerate(sample)]
common.save(rows, "sample.json")

ids = {(a, b): k for k, a, b, _ in rows}
out = []
for a, b, s in tk:
    if s >= .80:
        out.append([ids.get((a, b), 10000 + len(out)), a, b, round(s, 3)])
common.save(out, "all80.json")

random.seed(11)
common.save([{"id": c["id"], "question": c["question"], "parent": c["parent"], "claim": c["text"]}
             for c in random.sample(claims, 150)], "sc_sample.json")
print(len(rows), "labelling pairs,", len(out), "pairs at cos >= .80")
