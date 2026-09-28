"""The consensus layers wlo / jnb / woe at their Semantics.kt defaults, and a snapshot's credence graph.

`Graph.evaluate(shift)` recomputes every node bottom-up (a node = its stance + its incoming
edges' strength and source credence; an edge = its own stance + edges targeting it), adding
`shift[ref]` to a node's per-layer logits, so an adjusted claim's change reaches its ancestors.
"""
import math
import common

def lg(p): return math.log(p / (1 - p))
def sg(z): return 1 / (1 + math.exp(-z))
def clamp_base(b): return min(max(b, .01), .99)  # DfQuad.BASE_FLOOR
def pnorm(xs, p=2): return sum(x ** p for x in xs) ** (1 / p) if xs else 0.0

def wlo(base, A, S):  # arguments are (strength, credence) pairs
    g = lambda s, c: min(max(s * c, 0), 1) ** 1.3
    return sg(lg(clamp_base(base)) + 2.4 * (pnorm([g(*e) for e in S]) - pnorm([g(*e) for e in A])))
def jnb(base, A, S):
    def energy(s, c):
        s = min(max(s, 0), .8)
        lr = ((1 + s) / (1 - s)) ** .7
        return math.log(c * lr + (1 - c))
    return sg(lg(clamp_base(base)) + pnorm([energy(*e) for e in S]) - pnorm([energy(*e) for e in A]))
def woe(base, A, S):
    w = lambda s, c: -math.log(1 - min(max(s * c, 0), .7))
    return sg(lg(clamp_base(base)) + 1.2 * (pnorm([w(*e) for e in S]) - pnorm([w(*e) for e in A])))
LAYERS = {"wlo": wlo, "jnb": jnb, "woe": woe}
def consensus(v): return sg(sum(lg(min(max(v[k], .001), .999)) for k in LAYERS) / len(LAYERS))


class Graph:
    def __init__(self, run):
        g = common.graph(run)
        self.nodes = {n["ref"]: n for n in g["nodes"]}
        self.by_text = {n["text"]: n for n in g["nodes"] if n["kind"] == "CLAIM" and "text" in n}
        self.roots = {q["root"]: q["text"] for q in g["questions"]}
        self.incoming = {}
        for n in g["nodes"]:
            if n["kind"] == "EDGE":
                self.incoming.setdefault(n["target"], []).append(n)

    def stance(self, n):
        if n["kind"] == "EDGE":
            return n.get("strength") if n.get("strength") is not None else .5
        return n["plausibility"] if n.get("plausibility") is not None else .5

    def node_layers(self, node, cred, extra_S=(), extra_A=()):
        """Layer values of [node] given credence vectors [cred] (ref -> layer -> value) for its inputs."""
        out = {}
        for k, f in LAYERS.items():
            S, A = [], []
            for e in self.incoming.get(node["ref"], []):
                (S if e["polarity"] == "SUPPORT" else A).append((cred(e["ref"])[k], cred(e["source"])[k]))
            S += [(s, c[k]) for s, c in extra_S]
            A += [(s, c[k]) for s, c in extra_A]
            out[k] = f(self.stance(node), A, S)
        return out

    def evaluate(self, shift=None):
        """Every node's layer vector, recomputed bottom-up; shift: ref -> logit delta added to that node's layers."""
        shift = shift or {}
        memo = {}
        def cred(ref):
            if ref not in memo:
                v = self.node_layers(self.nodes[ref], cred)
                d = shift.get(ref, 0.0)
                memo[ref] = {k: sg(lg(min(max(x, 1e-9), 1 - 1e-9)) + d) for k, x in v.items()} if d else v
            return memo[ref]
        for ref in self.nodes:
            cred(ref)
        return memo
