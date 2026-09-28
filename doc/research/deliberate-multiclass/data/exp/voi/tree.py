"""Tree model + all binary VoI variants for computenet-dw2wh / 1ow0x.
Layer ports from ../../verify/math/verify.py (checked vs Kotlin to <=5e-16), plus euler/qe ported from
demo/deliberate Semantics.kt. Base = DfQuad.base = clamp(p, .01, .99) for every layer."""
import math, random, sys
sys.path.insert(0, "../../verify/math")
import verify as V

def euler(b, att, sup):
    b = V.clampBase(b); E = sum(sup) - sum(att)
    return 1 - (1 - b * b) / (1 + b * math.exp(E))
def qe(b, att, sup):
    b = V.clampBase(b); E = sum(sup) - sum(att)
    h = lambda x: max(0.0, x) ** 2 / (1 + max(0.0, x) ** 2)
    return b - b * h(-E) + (1 - b) * h(E)
def dfq(b, att, sup): return V.dfquad(b, att, sup)

# layer id -> (combine(base, attE, supE), energy(s, c))
LAY = {
    "dfquad": (dfq, V.energy_prod), "wlo": (V.wlo, V.energy_prod), "jnb": (V.jnb, V.jnb_energy),
    "woe": (V.woe, V.energy_prod), "euler": (euler, V.energy_prod), "qe": (qe, V.energy_prod),
    "mlp": (V.mlp, V.energy_prod),
}
IDS = list(LAY)
MEMBERS = ["wlo", "jnb", "woe"]
LO, HI = 0.001, 0.999
H = 1e-6

class Node:
    __slots__ = ("id", "p", "kids", "parent", "pol", "s", "depth", "c")
    def __init__(self, p, parent=None, pol=0, s=None, depth=0):
        self.p = p; self.kids = []; self.parent = parent; self.pol = pol; self.s = s; self.depth = depth; self.c = {}

def local(node, l, over_child=None, over_val=None, over_strength=None):
    """node's layer-l credence; optionally child over_child's credence := over_val, or its edge strength := over_strength."""
    f, en = LAY[l]; sup, att = [], []
    for k in node.kids:
        c = over_val if (k is over_child and over_val is not None) else k.c[l]
        s = over_strength if (k is over_child and over_strength is not None) else k.s
        (sup if k.pol > 0 else att).append(en(s, c))
    return f(node.p, att, sup)

def evaluate(root):
    def go(n):
        for k in n.kids: go(k)
        for l in IDS: n.c[l] = local(n, l)
    go(root)

class Headline:
    def __init__(self, kind): self.kind = kind  # 'consensus' or a layer id
    def layers(self): return MEMBERS if self.kind == "consensus" else [self.kind]
    def of(self, vals):  # vals: layer -> credence
        if self.kind != "consensus": return vals[self.kind]
        return V.sig(sum(V.logit(min(max(vals[l], LO), HI)) for l in MEMBERS) / len(MEMBERS))
    def grad(self, vals):
        if self.kind != "consensus": return {self.kind: 1.0}
        cons = self.of(vals); g = {}
        for l in MEMBERS:
            c = vals[l]; g[l] = 0.0 if (c <= LO or c >= HI) else cons * (1 - cons) / (len(MEMBERS) * c * (1 - c))
        return g

def node_headline(hl, n): return hl.of(n.c)

# ---------- exact path re-evaluation (cost: len(path) evaluate() per layer per resolution) ----------
def resolved_root(n, r, l, edge=False):
    """root layer-l credence when node n's credence (or, edge=True, its edge strength) is set to r."""
    if edge:
        val = local(n.parent, l, over_child=n, over_strength=r); cur = n.parent
    else:
        if n.parent is None: return r
        val = local(n.parent, l, over_child=n, over_val=r); cur = n.parent
    while cur.parent is not None:
        val = local(cur.parent, l, over_child=cur, over_val=val); cur = cur.parent
    return val

def root_of(n):
    while n.parent is not None: n = n.parent
    return n

def R(hl, n, r, edge=False): return hl.of({l: resolved_root(n, r, l, edge) for l in hl.layers()})

# ---------- tangent (today's Kotlin: central differences, one-sided at [0,1] ends) ----------
def diff(x, at):
    lo, hi = max(x - H, 0.0), min(x + H, 1.0)
    return 0.0 if hi <= lo else (at(hi) - at(lo)) / (hi - lo)

def secant(at): return at(1.0) - at(0.0)

def sens_layer(n, l, hop="tan", first_secant=False, edge=False):
    """d root_l / d node_l (or d root_l / d strength): product of local partials along the path."""
    g = 1.0; child = n; first = True
    if edge:
        par = n.parent
        g *= diff(n.s, lambda v: local(par, l, over_child=n, over_strength=v)); child = par; first = False
    while child.parent is not None:
        par = child.parent; x = child.c[l]
        use_sec = hop == "sec" or (first_secant and first)
        if use_sec: g *= secant(lambda v: local(par, l, over_child=child, over_val=v))
        else: g *= diff(x, lambda v: local(par, l, over_child=child, over_val=v))
        child = par; first = False
    return g

def scalar_sens(hl, n, **kw):
    root = root_of(n); g = hl.grad(root.c)
    return sum(g[l] * sens_layer(n, l, **kw) for l in g)

def unc(x): x = min(max(x, 0.0), 1.0); return 4 * x * (1 - x)

# ---------- response-curve cells: each node gets root_l(x_node) on a G-point grid (cell-compatible, one-way) ----------
def curve_R(hl, n, r, G, edge=False):
    """approximate R(node:=r) by composing G-point grid response curves top-down, linear interpolation."""
    xs = [i / (G - 1) for i in range(G)]
    def interp(tab, x):
        x = min(max(x, 0.0), 1.0); j = min(int(x * (G - 1)), G - 2); t = x * (G - 1) - j
        return tab[j] * (1 - t) + tab[j + 1] * t
    tgt = n.parent if edge else n
    path = []; cur = tgt
    while cur.parent is not None: path.append(cur); cur = cur.parent
    vals = {}
    for l in hl.layers():
        tab = xs[:]
        for child in reversed(path):
            par = child.parent
            tab = [interp(tab, local(par, l, over_child=child, over_val=x)) for x in xs]
        vals[l] = interp(tab, local(n.parent, l, over_child=n, over_strength=r) if edge else r)
    return hl.of(vals)

# ---------- tree generator ----------
def plaus(rnd):
    u = rnd.random()
    if u < 0.35: return rnd.choice([0.0, 0.25, 0.5, 0.75, 1.0, 0.9, 0.1])
    return rnd.betavariate(1.3, 1.3)
def strength(rnd):
    u = rnd.random()
    if u < 0.4: return rnd.choice([0.25, 0.5, 0.75, 1.0])
    return rnd.uniform(0.1, 1.0)

def gen_tree(rnd, maxdepth=None, maxb=4, cap=180, psup=0.55):
    D = maxdepth or rnd.randint(1, 4)
    root = Node(plaus(rnd)); nodes = [root]; frontier = [root]
    while frontier:
        n = frontier.pop(0)
        if n.depth >= D: continue
        if n.depth > 0 and rnd.random() < 0.3: continue
        for _ in range(rnd.randint(1, maxb)):
            if len(nodes) >= cap: break
            k = Node(plaus(rnd), n, 1 if rnd.random() < psup else -1, strength(rnd), n.depth + 1)
            n.kids.append(k); nodes.append(k); frontier.append(k)
    evaluate(root)
    return root, nodes
