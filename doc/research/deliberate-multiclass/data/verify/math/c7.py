import random, math
import voi_check as Q, verify as V

def hybrid(root, top, leaf, p, members):
    """design's eM at K=2: top-level claim credence linearised c_top + g_top*(r-c_leaf), root layer evaluated exactly."""
    # per layer: g_l = d top_l / d leaf ; root_l exact with overridden top
    r0 = Q.consensus([Q.cred(root, l) for l in members]) if members else None
    out = 0
    for r, pr in ((1.0, p), (0.0, 1 - p)):
        vals = []
        for l in members:
            c = Q.cred(leaf, l); ct = Q.cred(top, l); h = 1e-6
            g = (Q.cred(top, l, (leaf, c + h)) - Q.cred(top, l, (leaf, c - h))) / (2 * h)
            vals.append(Q.cred(root, l, (top, min(max(ct + g * (r - c), 0), 1))))
        out += pr * abs(Q.consensus(vals) - r0)
    return 2 * out

def row(name, root, top, leaf, p, members):
    Q.MEMBERS[:] = members
    leaf["base"] = p
    g = Q.sens(root, leaf); r0 = Q.headline(root)
    lin = abs(g) * 4 * p * (1 - p)
    ex = 2 * (p * abs(Q.exact_resolve(root, leaf, 1) - r0) + (1 - p) * abs(Q.exact_resolve(root, leaf, 0) - r0))
    hy = hybrid(root, top, leaf, p, members)
    print(f"| {name} | {'+'.join(members)} | {lin:.4f} | {hy:.4f} | {ex:.4f} |")

print("## C4/C7: today (=linear eM) vs design eM (tree linear, combination exact) vs fully exact, K=2")
print("| case | headline | today/linear | design hybrid | exact |\n|---|---|---|---|---|")
# top-level claim itself resolved (tree part identity): isolates combination nonlinearity
for s in (0.5, 0.9):
    leaf = {"base": 0.5}; root = {"base": 0.5, "kids": [(+1, s, leaf)]}
    row(f"top-level claim s={s}, p=.5", root, leaf, leaf, 0.5, ["wlo", "jnb", "woe"])
    row(f"top-level claim s={s}, p=.5", root, leaf, leaf, 0.5, ["woe"])
# WoE clamp: top credence ~.72 with s=1 -> energy clamped at .7 -> woe gradient 0
leaf = {"base": 0.5}; top = {"base": 0.6, "kids": [(+1, 0.95, leaf)]}; root = {"base": 0.5, "kids": [(+1, 1.0, top)]}
print("top credence per layer:", {l: round(Q.cred(top, l), 3) for l in ["wlo", "jnb", "woe"]})
row("leaf under top(s=1) near WoE emax", root, top, leaf, 0.5, ["woe"])
row("leaf under top(s=1) near WoE emax", root, top, leaf, 0.5, ["wlo", "jnb", "woe"])
# strong leaf under strong top, p=.9
leaf = {"base": 0.5}; top = {"base": 0.3, "kids": [(+1, 0.95, leaf)]}; root = {"base": 0.5, "kids": [(+1, 0.95, top), (-1, 0.9, {"base": 0.8})]}
for p in (0.5, 0.9, 0.1):
    row(f"decisive leaf, p={p}", root, top, leaf, p, ["wlo", "jnb", "woe"])

# random trees: leaf at depth 2-4, consensus headline
rnd = random.Random(11); Q.MEMBERS[:] = ["wlo", "jnb", "woe"]
ratios = []; zeroish = 0; n = 0
def build(d):
    if d == 0: return {"base": rnd.uniform(.05, .95)}, None
    kid, leaf = build(d - 1)
    node = {"base": rnd.uniform(.05, .95), "kids": [(rnd.choice([1, -1]), rnd.uniform(.2, 1), kid)] +
            [(rnd.choice([1, -1]), rnd.uniform(.2, 1), {"base": rnd.uniform(.05, .95)}) for _ in range(rnd.randint(0, 2))]}
    return node, (leaf or kid)
for _ in range(1500):
    root, leaf = build(rnd.randint(1, 4)); p = leaf["base"]
    g = Q.sens(root, leaf); r0 = Q.headline(root); lin = abs(g) * 4 * p * (1 - p)
    ex = 2 * (p * abs(Q.exact_resolve(root, leaf, 1) - r0) + (1 - p) * abs(Q.exact_resolve(root, leaf, 0) - r0))
    if ex < 1e-4: continue
    n += 1; ratios.append(lin / ex)
    if lin < 0.01 <= ex: zeroish += 1
ratios.sort()
q = lambda f: ratios[int(f * (len(ratios) - 1))]
print(f"\nrandom trees (n={n}, exact eM>=1e-4): linear/exact quantiles 5%={q(.05):.2f} 25%={q(.25):.2f} 50%={q(.5):.2f} 75%={q(.75):.2f} 95%={q(.95):.2f}; min={ratios[0]:.2f} max={ratios[-1]:.2f}")
print(f"cases where linear < voiEps 0.01 but exact >= 0.01 (premature DIMINISHING): {zeroish}")
