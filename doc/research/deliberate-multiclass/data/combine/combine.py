"""Combination rules: (claims with energy e_i = s_i*c_i, compatibility profile kappa_i over classes) -> distribution.

stdlib only. A claim is (e, kappa) where kappa maps class -> [0,1]; a class missing from kappa is
UNJUDGED and defaults to 1 (open-world withholding: never penalise what nobody judged).
OMEGA ("none of these / other") is an ordinary class that is unjudged unless stated.
"""
import itertools, math, random

OMEGA = "OTHER"
LOW, HIGH = 0.001, 0.999

def logit(p): p = min(max(p, LOW), HIGH); return math.log(p / (1 - p))
def sig(z): return 1 / (1 + math.exp(-z))
def pnorm(xs, p): return sum(x ** p for x in xs) ** (1 / p) if xs else 0.0
def normalise(d):
    t = sum(d.values()); return {k: v / t for k, v in d.items()}
def softmax(scores):
    m = max(scores.values()); w = {k: math.exp(v - m) for k, v in scores.items()}; return normalise(w)

def kap(claim, k): return claim[1].get(k, 1.0)

# ---------- priors from Jev per-class plausibility ----------
def prior(plaus, open_world=True, omega_min=0.05):
    """pi_k = p_k; pi_OMEGA = max(omega_min, 1 - sum p); normalise. Closed world: just normalise."""
    d = dict(plaus)
    if open_world: d[OMEGA] = max(omega_min, 1 - sum(plaus.values()))
    return normalise(d)

# ---------- (c) log-linear family: the K-class generalisation of the binary log-odds layers ----------
def loglinear(claims, pi, w, p=1.0, k=1.0, alpha=1.0):
    """score_k = alpha*log pi_k - k*||{ w(e_i)*(1-kappa_ik) }_i||_p ; softmax.
    K=2, kappa in {0,1}: logit P(A) = alpha*logit b + k(||W_S||_p - ||W_A||_p) -- exactly the binary layer."""
    sc = {}
    for c in pi:
        pen = [w(e) * (1 - kap((e, kp), c)) for e, kp in claims]
        sc[c] = alpha * math.log(pi[c]) - k * pnorm([x for x in pen if x > 0], p)
    return softmax(sc)

W_WOE = lambda e: -math.log(1 - min(e, 0.7))      # WeightOfEvidence, emax 0.7
W_EXACT = lambda e: -math.log(1 - min(e, 0.999))  # Pearl virtual evidence / Jeffrey-withhold, unclamped
W_MLP = lambda e: e
def bayes_ve(claims, pi): return loglinear(claims, pi, W_EXACT, p=1, k=1)
def ll_woe(claims, pi): return loglinear(claims, pi, W_WOE, p=2, k=1.2)
def ll_mlp(claims, pi): return loglinear(claims, pi, W_MLP, p=1, k=1)

# ---------- (d) model A baseline: independent per-class binary credence, then softmax(logit/T) ----------
def model_a(claims, plaus, T=1.0):
    """per class: WoE binary layer, supports = claims with kappa_k=1, attacks = kappa_k=0; then Softmax.shares."""
    sc = {}
    for c, b in plaus.items():
        sup = [W_WOE(e) * kap((e, kp), c) for e, kp in claims]
        att = [W_WOE(e) * (1 - kap((e, kp), c)) for e, kp in claims]
        pk = sig(logit(b) + 1.2 * (pnorm([x for x in sup if x > 0], 2) - pnorm([x for x in att if x > 0], 2)))
        sc[c] = logit(pk) / T
    return softmax(sc)

# ---------- (b) Dempster-Shafer ----------
def mass_of(claim, frame):
    """0/1 kappa -> simple support m(S)=e, m(frame)=1-e. Graded kappa -> consonant (alpha-cut) mass (Dubois-Prade)."""
    e, _ = claim
    levels = sorted({kap(claim, c) for c in frame}, reverse=True)
    m = {}
    top = levels[0]
    for j, a in enumerate(levels):
        if a <= 0: break
        nxt = levels[j + 1] if j + 1 < len(levels) else 0.0
        S = frozenset(c for c in frame if kap(claim, c) >= a)
        m[S] = m.get(S, 0) + e * (a - nxt) / top
    F = frozenset(frame)
    m[F] = m.get(F, 0) + 1 - e
    return m

def conj(m1, m2):
    out = {}
    for A, a in m1.items():
        for B, b in m2.items():
            C = A & B; out[C] = out.get(C, 0) + a * b
    return out

def tbm(claims, frame):
    m = {frozenset(frame): 1.0}
    for cl in claims: m = conj(m, mass_of(cl, frame))
    return m  # m[frozenset()] = conflict, kept (Smets)

def pcr6(claims, frame):
    ms = [mass_of(cl, frame) for cl in claims]
    if not ms: return {frozenset(frame): 1.0}
    out = {}
    for combo in itertools.product(*[list(m.items()) for m in ms]):
        sets = [s for s, _ in combo]; ws = [w for _, w in combo]
        prod = math.prod(ws); inter = frozenset.intersection(*sets)
        if inter:
            out[inter] = out.get(inter, 0) + prod
        else:  # PCR6 (Martin & Osswald 2006): give conflict back to each X_j prop. to m_j(X_j)
            tot = sum(ws)
            for s, w_ in zip(sets, ws): out[s] = out.get(s, 0) + prod * w_ / tot
    return out

def betp(m, pi, empty_to=None):
    """Pignistic transform weighted by base rates pi (Smets BetP generalised as in Josang's projection).
    empty_to=None: condition away m(empty) (=Dempster). empty_to=OMEGA: Smets open-world reading."""
    out = {c: 0.0 for c in pi}
    for S, v in m.items():
        if not S:
            if empty_to: out[empty_to] += v
            continue
        z = sum(pi[c] for c in S)
        for c in S: out[c] += v * pi[c] / z
    return normalise(out)

def dempster(claims, pi): return betp(tbm(claims, list(pi)), pi)
def tbm_open(claims, pi): return betp(tbm(claims, list(pi)), pi, empty_to=OMEGA) if OMEGA in pi else dempster(claims, pi)
def pcr(claims, pi): return betp(pcr6(claims, list(pi)), pi)
def conflict(claims, pi): return tbm(claims, list(pi)).get(frozenset(), 0.0)

# ---------- (a) subjective logic: hyper-opinion, cumulative fusion = evidence addition ----------
def sl(claims, pi, N=10.0, W=2.0, complement=False):
    """Claim adds evidence N*e to its compatible set S (0/1 cut at kappa>=0.5); complement=True also adds
    N*(s - e) to the complement (disbelief -> complement). Projection P_k = sum_S r_S pi_k/pi(S) + W pi_k, / (W+sum r)."""
    r = {}
    for e, kp in claims:
        S = frozenset(c for c in pi if kap((e, kp), c) >= 0.5)
        r[S] = r.get(S, 0) + N * e
        if complement:
            Sc = frozenset(pi) - S
            if Sc: r[Sc] = r.get(Sc, 0) + N * (1 - e)
    tot = W + sum(r.values())
    out = {c: W * pi[c] for c in pi}
    for S, v in r.items():
        z = sum(pi[c] for c in S)
        for c in S: out[c] += v * pi[c] / z
    return {c: v / tot for c, v in out.items()}, W / tot  # (projected P, vacuity u)

def sl_p(claims, pi): return sl(claims, pi)[0]

# ---------- consensus across rules: log pool (K=2: sigmoid(mean logit) == Consensus.ofValues) ----------
def logpool(ds):
    ks = ds[0].keys()
    return normalise({k: math.exp(sum(math.log(max(d[k], 1e-9)) for d in ds) / len(ds)) for k in ks})
def jsd(ds):
    M = {k: sum(d[k] for d in ds) / len(ds) for k in ds[0]}
    kl = lambda P: sum(P[k] * math.log(P[k] / M[k]) for k in P if P[k] > 0)
    return sum(kl(d) for d in ds) / len(ds)

RULES = [("modelA", None), ("bayesVE", bayes_ve), ("LL-woe", ll_woe), ("LL-mlp", ll_mlp),
         ("Dempster", dempster), ("TBM-open", tbm_open), ("PCR6", pcr), ("SL", sl_p)]
POOL = ["bayesVE", "LL-woe", "LL-mlp", "TBM-open", "PCR6", "SL"]

def run(title, claims, plaus, open_world=True, show=None):
    pi = prior(plaus, open_world)
    classes = list(pi)
    res = {}
    for name, f in RULES:
        res[name] = model_a(claims, plaus) if name == "modelA" else f(claims, pi)
    print(f"\n### {title}")
    print("| rule | " + " | ".join(classes) + " |")
    print("|---|" + "---|" * len(classes))
    print("| prior pi | " + " | ".join(f"{pi[c]:.3f}" for c in classes) + " |")
    for name, _ in RULES:
        print(f"| {name} | " + " | ".join(f"{res[name].get(c, float('nan')):.3f}" if c in res[name] else "—" for c in classes) + " |")
    pool = logpool([res[n] for n in POOL])
    print("| **log-pool** | " + " | ".join(f"**{pool[c]:.3f}**" for c in classes) + " |")
    u = sl(claims, pi)[1]
    print(f"\nTBM conflict m(∅)={conflict(claims, pi):.3f}; SL vacuity u={u:.3f}; JSD across pooled rules={jsd([res[n] for n in POOL]):.3f}")
    return res

def continuity():
    """K=2, supports compatible {A}, attacks compatible {notA}; compare each rule with the binary layers."""
    rnd = random.Random(7)
    worst = {}
    def dfquad(b, att, sup):
        ps = lambda xs: 1 - math.prod(1 - x for x in xs)
        es, ea = ps(sup), ps(att)
        return b + (1 - b) * (es - ea) if es >= ea else b - b * (ea - es)
    def woe_bin(b, att, sup):
        return sig(logit(b) + 1.2 * (pnorm([W_WOE(e) for e in sup], 2) - pnorm([W_WOE(e) for e in att], 2)))
    def mlp_bin(b, att, sup): return sig(logit(b) + sum(sup) - sum(att))
    onesided = {}
    for _ in range(2000):
        b = rnd.uniform(0.05, 0.95)
        sup = [rnd.uniform(0, 0.9) for _ in range(rnd.randint(0, 3))]
        att = [rnd.uniform(0, 0.9) for _ in range(rnd.randint(0, 3))]
        claims = [(e, {"A": 1, "notA": 0}) for e in sup] + [(e, {"A": 0, "notA": 1}) for e in att]
        pi = {"A": b, "notA": 1 - b}
        pairs = {
            "LL-woe vs WoE layer": (ll_woe(claims, pi)["A"], woe_bin(b, att, sup)),
            "LL-mlp vs MLP layer": (ll_mlp(claims, pi)["A"], mlp_bin(b, att, sup)),
            "Dempster+BetP vs DF-QuAD": (dempster(claims, pi)["A"], dfquad(b, att, sup)),
            "PCR6+BetP vs DF-QuAD": (pcr(claims, pi)["A"], dfquad(b, att, sup)),
            "SL vs DF-QuAD": (sl_p(claims, pi)["A"], dfquad(b, att, sup)),
            "modelA(T=1) vs WoE layer": (model_a(claims, {"A": b, "notA": 1 - b})["A"], woe_bin(b, att, sup)),
            "modelA(T=2) vs WoE layer": (model_a_T2(claims, {"A": b, "notA": 1 - b})["A"], woe_bin(b, att, sup)),
        }
        for k, (x, y) in pairs.items():
            worst[k] = max(worst.get(k, 0), abs(x - y))
            if not att or not sup: onesided[k] = max(onesided.get(k, 0), abs(x - y))
    print("\n### K=2 continuity (max |P_rule(A) - P_binary(A)| over 2000 random trees)")
    print("| comparison | all trees | one-sided trees |\n|---|---|---|")
    for k in worst: print(f"| {k} | {worst[k]:.4f} | {onesided.get(k, 0):.4f} |")

def model_a_T2(claims, plaus): return model_a(claims, plaus, T=2.0)

if __name__ == "__main__":
    continuity()

    # (i) fate of the universe. e = s*c.
    RIP, CRUNCH, FREEZE = "rip", "crunch", "freeze"
    plaus = {RIP: 0.15, CRUNCH: 0.20, FREEZE: 0.70}
    c1 = (0.95 * 0.8, {RIP: 1, CRUNCH: 0, FREEZE: 1})               # expansion accelerating
    c2 = (0.60 * 0.9, {RIP: 0, CRUNCH: 0, FREEZE: 1, OMEGA: 0.2})   # dark energy is Lambda (w=-1)
    c3 = (0.45 * 0.6, {RIP: 0, CRUNCH: 1, FREEZE: 1})               # DESI: dark energy weakening
    c4 = (0.70 * 0.7, {RIP: 0, CRUNCH: 1, FREEZE: 1})               # phantom w<-1 disfavoured (NEC)
    run("(i) fate of the universe, 4 claims, open world", [c1, c2, c3, c4], plaus)
    run("(i') same, all claims doubted (c=0.1 each)", [(0.1 * 0.8, c1[1]), (0.1 * 0.9, c2[1]), (0.1 * 0.6, c3[1]), (0.1 * 0.7, c4[1])], plaus)

    # (ii) Zadeh. Two near-certain sources, graded profiles.
    A, B, C = "mening", "concuss", "tumour"
    z1 = {A: 1, B: 0.01, C: 0}; z2 = {A: 0, B: 0.01, C: 1}
    zp = {A: 1 / 3, B: 1 / 3, C: 1 / 3}
    run("(ii-a) Zadeh, e=0.99 (unclamped), closed world", [(0.99, z1), (0.99, z2)], zp, open_world=False)
    run("(ii-b) Zadeh, e=0.99, open world (OTHER unjudged)", [(0.99, z1), (0.99, z2)], {A: .3, B: .3, C: .3})
    run("(ii-c) Zadeh, e=0.7 (energy clamp as WoE emax), closed world", [(0.7, z1), (0.7, z2)], zp, open_world=False)

    # (iii) none of these.
    X, Y, Z = "X", "Y", "Z"
    elim = lambda k, e: (e, {c: (0 if c == k else 1) for c in (X, Y, Z)})  # OTHER unjudged -> compatible
    run("(iii-a) all positions implausible by prior (p=0.1 each), no arguments", [], {X: .1, Y: .1, Z: .1})
    run("(iii-b) plausible priors (0.3 each), each position eliminated by a claim e=0.64",
        [elim(X, .64), elim(Y, .64), elim(Z, .64)], {X: .3, Y: .3, Z: .3})
    run("(iii-c) as (iii-b), closed world (no OTHER class)",
        [elim(X, .64), elim(Y, .64), elim(Z, .64)], {X: .3, Y: .3, Z: .3}, open_world=False)

    # (iv) class added mid-deliberation: big bounce / cyclic.
    BOUNCE = "bounce"
    base3 = [c1, c2, c4]
    run("(iv-a) before: 3 claims, 3 classes", base3, plaus)
    p4 = dict(plaus, **{BOUNCE: 0.10})
    run("(iv-b) bounce added, links UNJUDGED -> default compatible (inherits OTHER)", base3, p4)
    closed = [(e, dict(kp, **{BOUNCE: 0})) for e, kp in base3]
    run("(iv-c) bounce added, unjudged -> default INcompatible (closed default, wrong)", closed, p4)
    judged = [(c1[0], dict(c1[1], **{BOUNCE: 0.5})), (c2[0], dict(c2[1], **{BOUNCE: 0})), (c4[0], dict(c4[1], **{BOUNCE: 1}))]
    run("(iv-d) bounce links re-judged (0.5, 0, 1)", judged, p4)

    # (v) overlapping: causes of the 2008 crisis (several can be true).
    SUB, LEV, DEREG, IMB = "subprime", "leverage", "dereg", "imbalance"
    ov_plaus = {SUB: .6, LEV: .6, DEREG: .5, IMB: .4}
    ov = [(0.9 * 0.8, {SUB: 1, LEV: 1, DEREG: 1, IMB: 1} | {SUB: 1}),  # placeholder, replaced below
          ]
    # MECE rules need 'compatible sets'; under the EITHER reading a pro-subprime claim is compatible with all
    # causes (it rules nothing out), so it is inert. Under a forced 'this is THE cause' reading it rules the
    # others out. Show the forced reading (what a naive annotator produces) and per-class binary marginals.
    forced = [(0.9 * 0.8, {SUB: 1, LEV: 0, DEREG: 0, IMB: 0}),
              (0.85 * 0.8, {SUB: 0, LEV: 1, DEREG: 0, IMB: 0}),
              (0.5 * 0.6, {SUB: 1, LEV: 1, DEREG: 0, IMB: 1}),
              (0.6 * 0.5, {SUB: 0, LEV: 0, DEREG: 0, IMB: 1})]
    run("(v-a) overlapping causes pushed through MECE rules (forced 'the cause' reading)", forced, ov_plaus, open_world=False)
    # per-class binary marginals: each class its own root (today's machinery), claim = support(+)/attack(-) per class
    signed = [(0.9 * 0.8, {SUB: +1}), (0.85 * 0.8, {LEV: +1}), (0.5 * 0.6, {DEREG: -1}), (0.6 * 0.5, {IMB: +1})]
    print("\n### (v-b) same causes as K independent binary roots (WoE layer), marginals")
    print("| class | base | marginal |\n|---|---|---|")
    for c, b in ov_plaus.items():
        sup = [W_WOE(e) for e, kp in signed if kp.get(c) == 1]
        att = [W_WOE(e) for e, kp in signed if kp.get(c) == -1]
        print(f"| {c} | {b:.2f} | {sig(logit(b) + 1.2 * (pnorm(sup, 2) - pnorm(att, 2))):.3f} |")
