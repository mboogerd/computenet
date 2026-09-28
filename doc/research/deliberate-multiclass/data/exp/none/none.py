"""None-of-these (Ω) designs for categorical deliberate roots (computenet-1ow0x). Stdlib only.

Layer semantics come from the verified faithful Kotlin ports in ../../verify/math/verify.py
(wlo, woe, jnb, mlp; LL(claims, pi, p, k) is the closed-world log-linear elimination that is
exact per layer at K=2). combine.py is NOT used (known bugs: mass_of drops mass at all-κ=0,
continuity() omits wlo/jnb); the TBM variant below has its own fixed mass function.

A listed claim: dict(s, c, kappa={class: [0,1]}, kind in {pos, neg, contrast}).
  kind is the LATENT truth a judge is asked about, never read directly by a combiner:
   pos      positive evidence for its compatible set (would be unexpected if the answer were
            unlisted)  -> true kappa_Omega = 0
   neg      an absolute objection to its incompatible set (still expected if the answer were
            unlisted)  -> true kappa_Omega = 1
   contrast relative ('A fits better than B'); says nothing about Omega -> neutral
An L-claim (argued on the root "the answer is one of the listed"): dict(s, c, sign=+1 supports L,
  -1 attacks L = argues for none-of-these).
"""
import math, random, sys, os
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "../../verify/math"))
import verify as V  # faithful ports

LAYERS = ["wlo", "woe", "jnb", "mlp"]
OM = "Ω"

def energy(layer, s, c):
    return V.jnb_energy(s, c) if layer == "jnb" else V.energy_prod(s, c)

def bin_layer(layer, b, sup, att):
    """today's binary layer over energies (the object R1 is measured against)."""
    f = V.LAYERS[layer][0]
    return f(b, att, sup)

def ll_weight(layer, e): return V.LAYERS[layer][2](e)

def cond_shares(layer, claims, pi):
    """closed-world LL over listed classes (exact per layer at K=2)."""
    _, _, w, p, k = V.LAYERS[layer]
    cl = [(w(energy(layer, x["s"], x["c"])), x["kappa"]) for x in claims]
    pic = {c: V.clampBase(v) if len(pi) == 2 else v for c, v in pi.items()}
    return V.LL(cl, pic, p, k)

def penalties(layer, claims, classes, kfn=None):
    """per-class log-penalty k*||w(e)(1-kappa)||_p (kfn(claim, class) overrides kappa lookup)."""
    _, _, w, p, k = V.LAYERS[layer]
    out = {}
    for c in classes:
        pen = []
        for x in claims:
            kap = kfn(x, c) if kfn else x["kappa"].get(c, 1.0)
            pen.append(w(energy(layer, x["s"], x["c"])) * (1 - kap))
        out[c] = k * V.pnorm([v for v in pen if v > 0], p)
    return out

def softmax(sc):
    m = max(sc.values()); z = {c: math.exp(v - m) for c, v in sc.items()}; t = sum(z.values())
    return {c: v / t for c, v in z.items()}

def normpi(plaus):
    t = sum(plaus.values()); return {c: v / t for c, v in plaus.items()}

# ------------------------------------------------------------------ simulated judge
class Judge:
    """Simulated Jev. beta = the pilot's upgrade bias (reads 'counts against' as 'rules out',
    here: reads a contrastive/positive argument as an absolute objection). sd = noise."""
    def __init__(self, beta=0.0, sd=0.0, seed=0):
        self.beta, self.sd, self.r = beta, sd, random.Random(seed)
    def _n(self, x): return min(1.0, max(0.0, x + (self.r.gauss(0, self.sd) if self.sd else 0)))
    def absolute(self, claim):  # "would this objection still stand if the answer were unlisted?"
        t = 1.0 if claim["kind"] == "neg" else 0.0
        return self._n(t + self.beta * (1 - t))
    def positive(self, claim):  # "would this be unexpected if the answer were unlisted?"
        t = 1.0 if claim["kind"] == "pos" else 0.0
        return self._n(t * (1 - self.beta))
    def kappa_omega(self, claim, pi):  # per-claim Ω column (candidate b1)
        if claim["kind"] == "pos": t = 0.0
        elif claim["kind"] == "neg": t = 1.0
        else: t = sum(pi[c] * claim["kappa"].get(c, 1.0) for c in pi)  # neutral = prior-mixture
        return self._n(t + self.beta * (1 - t))

def nor(xs): return 1 - math.prod(1 - x for x in xs)

# ------------------------------------------------------------------ derived L-evidence (design H)
def derived(layer, listed, classes, judge, use_plus=False):
    """D-  'every listed answer faces a standing absolute objection'  -> attacks L
       structural credence S = min_k noisyOR_i e_i(1-kappa_ik)   (rebuttals lower c_i, so S falls)
       judged plausibility J = min_k max_i abs_i (1-kappa_ik)     (Jev sees the objection list)
       energy into L = J * S.   Optional D+ symmetric ('some listed answer has positive support')."""
    out_att, out_sup = [], []
    if listed:
        S = min(nor([x["s"] * x["c"] * (1 - x["kappa"].get(k, 1.0)) for x in listed]) for k in classes)
        J = min(max([judge.absolute(x) * (1 - x["kappa"].get(k, 1.0)) for x in listed] + [0]) for k in classes)
        if J * S > 0: out_att.append((J, S))  # (strength, credence) of the derived node's edge
        if use_plus:
            Sp = max(nor([x["s"] * x["c"] * x["kappa"].get(k, 1.0) * (1 - min(x["kappa"].get(j, 1.0) for j in classes if j != k))
                          for x in listed]) for k in classes)
            Jp = max([judge.positive(x) for x in listed] + [0])
            if Jp * Sp > 0: out_sup.append((Jp, Sp))
    return out_sup, out_att

def P_L(layer, bL, lclaims, extra_sup=(), extra_att=()):
    sup = [energy(layer, x["s"], x["c"]) for x in lclaims if x["sign"] > 0] + [energy(layer, s, c) for s, c in extra_sup]
    att = [energy(layer, x["s"], x["c"]) for x in lclaims if x["sign"] < 0] + [energy(layer, s, c) for s, c in extra_att]
    return bin_layer(layer, bL, sup, att)

# ------------------------------------------------------------------ candidates (one layer)
def cand(name, layer, sc, judge):
    classes = list(sc["plaus"]); pi = normpi(sc["plaus"]); bL = sc["bL"]
    listed, lcl = sc["listed"], sc.get("lclaims", [])
    s = cond_shares(layer, listed, pi)
    if name.startswith("O-flat") or name.startswith("N"):
        lam = 1.0 if name.startswith("O") else float(name[2:])
        piO = max(0.05, 1 - sum(sc["plaus"].values())) if name == "O-flat-resid" else 1 - bL
        pen = penalties(layer, listed, classes)  # unjudged Ω: kappa=1, no penalty
        # explicit L claims enter Ω's score as ordinary judged claims against/for the listed set
        penO = penalties(layer, [dict(x, kappa={OM: 0.0 if x["sign"] > 0 else 1.0}) for x in lcl], [OM])[OM]
        penL = penalties(layer, [dict(x, kappa={OM: 1.0 if x["sign"] > 0 else 0.0}) for x in lcl], [OM])[OM]
        logZ = math.log(sum(pi[c] * math.exp(-pen[c]) for c in classes))
        sc2 = {"L": math.log(1 - piO) + lam * logZ - penL, OM: math.log(piO) - penO}
        d = softmax(sc2); PL = d["L"]
    elif name == "B1-kappaΩ":  # Ω in the softmax, every claim gets a judged κ_Ω
        piO = 1 - bL
        allc = dict(pi);
        kfn = lambda x, c: judge.kappa_omega(x, pi) if c == OM else x["kappa"].get(c, 1.0)
        pen = penalties(layer, listed, classes + [OM], kfn)
        penL = penalties(layer, [dict(x, kappa={"L": 1.0 if x["sign"] > 0 else 0.0}) for x in lcl], ["L"])["L"]
        penO = penalties(layer, [dict(x, kappa={OM: 0.0 if x["sign"] > 0 else 1.0}) for x in lcl], [OM])[OM]
        logZ = math.log(sum(pi[c] * math.exp(-pen[c]) for c in classes))
        d = softmax({"L": math.log(1 - piO) + logZ - penL, OM: math.log(piO) - pen[OM] - penO}); PL = d["L"]
    elif name == "A0-hurdle":
        PL = P_L(layer, bL, lcl)
    elif name in ("H-hurdle+D", "H-hurdle+D±"):
        sup, att = derived(layer, listed, classes, judge, use_plus=name.endswith("±"))
        PL = P_L(layer, bL, lcl, sup, att)
    elif name == "T-TBM-open":
        return tbm_open(layer, sc)
    else:
        raise KeyError(name)
    out = {c: PL * s[c] for c in classes}; out[OM] = 1 - PL
    return out

def tbm_open(layer, sc):
    """Smets open world: frame = listed ∪ {Ω}? No: closed listed frame, m(∅) read as Ω (fixed mass fn)."""
    classes = list(sc["plaus"]); pi = normpi(sc["plaus"]); F = frozenset(classes)
    m = {F: 1.0}
    items = [(x["s"] * x["c"], x["kappa"]) for x in sc["listed"]]
    items += [(x["s"] * x["c"], {c: 1.0 if x["sign"] > 0 else 0.0 for c in classes}) for x in sc.get("lclaims", [])]
    for e, kp in items:
        levels = sorted({kp.get(c, 1.0) for c in classes}, reverse=True); mm = {}
        if levels[0] <= 0: mm[frozenset()] = e  # BUGFIX vs combine.mass_of: eliminates everything -> m(∅)
        else:
            for j, a in enumerate(levels):
                if a <= 0: break
                nxt = levels[j + 1] if j + 1 < len(levels) else 0.0
                S = frozenset(c for c in classes if kp.get(c, 1.0) >= a)
                mm[S] = mm.get(S, 0) + e * (a - nxt) / levels[0]
        mm[F] = mm.get(F, 0) + 1 - e
        new = {}
        for A_, a in m.items():
            for B_, b in mm.items():
                new[A_ & B_] = new.get(A_ & B_, 0) + a * b
        m = new
    # prior: Ω gets 1-bL as a vacuous-open share, then m(∅) added
    out = {c: 0.0 for c in classes}; out[OM] = m.get(frozenset(), 0.0)
    for S_, v in m.items():
        if not S_: continue
        z = sum(pi[c] for c in S_)
        for c in S_: out[c] += v * pi[c] / z
    bL = sc["bL"]
    return {c: (out[c] * bL if c != OM else out[OM] + (1 - out[OM]) * (1 - bL)) for c in out}

def logpool(ds):
    ks = list(ds[0]); g = {k: math.exp(sum(math.log(max(d[k], 1e-12)) for d in ds) / len(ds)) for k in ks}
    t = sum(g.values()); return {k: v / t for k, v in g.items()}

CANDS = ["O-flat-resid", "O-flat", "N-0.5", "B1-kappaΩ", "T-TBM-open", "A0-hurdle", "H-hurdle+D", "H-hurdle+D±"]

def pooled(name, sc, judge):
    return logpool([cand(name, L, sc, judge) for L in LAYERS])

# ------------------------------------------------------------------ scenario builders
def C(s, c, kappa, kind): return dict(s=s, c=c, kappa=kappa, kind=kind)
def LC(s, c, sign): return dict(s=s, c=c, sign=sign)
def pro(k, classes, s=.9, c=.9): return C(s, c, {j: (1.0 if j == k else 0.0) for j in classes}, "pos")
def con(k, classes, s=.9, c=.9): return C(s, c, {j: (0.0 if j == k else 1.0) for j in classes}, "neg")
def contrast(a, b, classes, s=.8, c=.8): return C(s, c, {j: (0.0 if j == b else 1.0) if j != a else 1.0 for j in classes}, "contrast")
