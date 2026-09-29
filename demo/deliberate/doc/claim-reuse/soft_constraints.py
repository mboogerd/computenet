# /// script
# dependencies = ["numpy", "scipy"]
# ///
"""Experiment g, step 2: minimal adjustment of credences under believed implications.

    minimise  sum_n a_n (z_n - z0_n)^2  +  sum_k s(w_k) * max(0, z_X(k) - z_Y(k))^2

z = logit of a claim's consensus credence (z0: as deliberated); implication k says "X implies Y"
with Jev-graded belief w_k (implication_belief.json), stiffness s(w) = w / (1 - w), w capped at .95.
Every direction with w >= W_MIN is a constraint. Settings:
  soft/uniform   a_n = 1
  soft/firmness  a_n = 1 + number of claims explored below n (a deeply deliberated claim moves less)
  hard           a_n = 1, s = 1e4 for every direction Jev's binary check (jev_entail_all80) called YES
Each result's claim shifts are pushed through the recomputed credence graph (layers.Graph) to the
question roots. Run: uv run soft_constraints.py
"""
import numpy as np
from scipy.optimize import minimize
import common, layers

W_MIN = 0.5
claims = common.load_claims()
pairs = {p["pair"]: p for p in common.expand(common.load("all80.json"), claims)}
belief = {int(k): v for k, v in common.load("implication_belief.json").items()}
binary = {int(k): v for k, v in common.load("jev_entail_all80.json").items()}
graphs = {run: layers.Graph(run) for run in common.RUNS}
node = lambda c: graphs[c["run"]].by_text[c["text"]]
z0_of = lambda c: layers.lg(min(max(node(c)["consensus"], 1e-6), 1 - 1e-6))

def descendants(g, ref):
    seen, todo = set(), [ref]
    while todo:
        r = todo.pop()
        for e in g.incoming.get(r, []):
            for x in (e["ref"], e["source"]):
                if x not in seen:
                    seen.add(x); todo.append(x)
    return sum(1 for x in seen if g.nodes[x]["kind"] == "CLAIM")

def constraints(mode):
    out = []  # (x claim id, y claim id, stiffness, w)
    for p, pr in pairs.items():
        for x, y, w, yes in ((pr["a"], pr["b"], belief[p]["w_ab"], binary[p]["ab"]), (pr["b"], pr["a"], belief[p]["w_ba"], binary[p]["ba"])):
            if mode == "hard":
                if yes == "YES":
                    out.append((x, y, 1e4, 1.0))
            elif w >= W_MIN:
                w = min(w, .95)
                out.append((x, y, w / (1 - w), w))
    return out

def solve(mode, firm):
    K = constraints(mode)
    ids = sorted({i for x, y, _, _ in K for i in (x, y)})
    idx = {c: i for i, c in enumerate(ids)}
    z0 = np.array([z0_of(claims[c]) for c in ids])
    a = np.array([1 + descendants(graphs[claims[c]["run"]], node(claims[c])["ref"]) if firm else 1.0 for c in ids])
    X = np.array([idx[x] for x, _, _, _ in K]); Y = np.array([idx[y] for _, y, _, _ in K]); s = np.array([k[2] for k in K])
    def f(z):
        v = np.maximum(0, z[X] - z[Y])
        g = 2 * a * (z - z0)
        np.add.at(g, X, 2 * s * v); np.add.at(g, Y, -2 * s * v)
        return float(np.sum(a * (z - z0) ** 2) + np.sum(s * v ** 2)), g
    z = minimize(f, z0, jac=True, method="L-BFGS-B", options={"maxiter": 5000, "gtol": 1e-10}).x
    return K, ids, z0, z, idx

def report(name, mode, firm):
    K, ids, z0, z, idx = solve(mode, firm)
    c0, c1 = 1 / (1 + np.exp(-z0)), 1 / (1 + np.exp(-z))
    viol = lambda c: [(c[idx[x]] - c[idx[y]], w) for x, y, _, w in K if c[idx[x]] - c[idx[y]] > .01]
    v0, v1 = viol(c0), viol(c1)
    move = np.abs(c1 - c0)
    flips = int(np.sum((c1 - .5) * (c0 - .5) < 0))
    shift = {}
    for c, d in zip(ids, z - z0):
        shift.setdefault(claims[c]["run"], {})[node(claims[c])["ref"]] = float(d)
    roots = []
    for run, g in graphs.items():
        before, after = g.evaluate(), g.evaluate(shift.get(run, {}))
        for r, q in g.roots.items():
            b, a2 = layers.consensus(before[r]), layers.consensus(after[r])
            roots.append((abs(a2 - b), (a2 - .5) * (b - .5) < 0, f"{run}:{q}", b, a2))
    print(f"\n{name}: {len(K)} constraints over {len(ids)} claims")
    print(f"  violated (c(X) > c(Y) + .01): before {len(v0)} (w>=.75: {sum(w >= .75 for _, w in v0)}), "
          f"after {len(v1)} (w>=.75: {sum(w >= .75 for _, w in v1)}), largest remaining gap {max([d for d, _ in v1], default=0):.3f}")
    print(f"  claim moves: mean {move.mean():.3f}, moved > .05: {int(np.sum(move > .05))}, max {move.max():.3f}, verdict flips {flips}")
    roots.sort(reverse=True)
    print(f"  question roots: max |Δ| {roots[0][0]:.4f}, flips {sum(r[1] for r in roots)}; largest: "
          + "; ".join(f"{q[:40]} {b:.3f}->{a2:.3f}" for _, _, q, b, a2 in roots[:3]))
    return K, ids, z0, z

if __name__ == "__main__":
    hard = report("hard (binary YES, stiff)", "hard", False)
    uni = report("soft, uniform a_n", "soft", False)
    firm = report("soft, firmness a_n = 1 + explored claims below", "soft", True)
    ws = [w for x, y, _, w in constraints("soft")]
    print(f"\nbelief w of the {len(ws)} included directions: .50-.74 {sum(w < .75 for w in ws)}, .75-.94 {sum(.75 <= w < .95 for w in ws)}, "
          f">= .95 (capped) {sum(w >= .95 for w in ws)}; all 1416 directions: w >= .5 in {len(ws)}")
    a = [1 + descendants(graphs[claims[c]['run']], node(claims[c])['ref']) for c in uni[1]]
    print(f"firmness a_n over constrained claims: a=1 (nothing explored below) {sum(x == 1 for x in a)}, a>1 {sum(x > 1 for x in a)}, max {max(a)}")
    K, ids, z0, z = firm
    d = z - z0
    top = np.argsort(-np.abs(d))[:4]
    print("largest moves (soft, firmness):")
    for i in top:
        c = claims[ids[i]]
        print(f"  {1 / (1 + np.exp(-z0[i])):.2f} -> {1 / (1 + np.exp(-z[i])):.2f}  {c['text'][:100]}")
        for x, y, _, w in K:
            if ids[i] in (x, y):
                o = y if x == ids[i] else x
                j = ids.index(o)
                print(f"      {'implies' if x == ids[i] else 'implied by'} (w {w:.2f}, other {1 / (1 + np.exp(-z0[j])):.2f} -> {1 / (1 + np.exp(-z[j])):.2f}): {claims[o]['text'][:90]}")
