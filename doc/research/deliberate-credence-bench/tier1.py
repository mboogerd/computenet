#!/usr/bin/env python3
"""Tier 1 of the deliberate credence benchmark: synthetic argument trees with an EXACT correct credence at every claim.
Run: uv run --with numpy --with scipy python tier1.py      (seeded, offline, no model calls, ~1 min)

Generative model (a Bayesian-network tree, edges parent -> child):
  - every claim X is a binary variable; the root has prior .5; a child C of P has P(C|P) = a, P(C|not P) = b.
    a > b makes C a SUPPORT of P (C true is evidence for P), a < b an ATTACK. The relation's judged STRENGTH is |a - b|.
    (a, b) are drawn freely, so relations range from symmetric (C false is evidence the other way) to argument-style
    (C false says little): the propagation rule is never told which.
  - every claim may carry its own direct evidence: an observation that is right with probability q (q = .5: none).
  - a claim's BASE is what a judge sees of it in isolation: P(X | its own direct evidence), with X's marginal prior.
    This is the engine's Jev stance.
  - TARGET at claim X: the exact P(X | all evidence in X's subtree), by upward belief propagation. That is precisely
    what the engine's node credence is meant to be, since a node reads only its own subtree.
  Every truth value is also sampled, so rules are also scored against reality (log loss).
The exact target is itself INCREMENTAL: log-odds(X) = logit(base_X) + sum over children of one message per child.
So a rule can, in principle, be both incremental and exactly right; the question is how close each one comes from
the inputs it gets (base, sign, strength, child credence, child base) without knowing (a, b).

Conditions: EXACT inputs (any error is the rule's), and NOISY inputs (base and strength perturbed, the judge's error).
Slices: by depth of the claim's subtree, by relation shape, and a near-logic slice (strong relations, near-certain leaves)."""
import json, math
from pathlib import Path
import numpy as np
from scipy.optimize import minimize

HERE = Path(__file__).resolve().parent
LO, HI = 1e-3, 1 - 1e-3
lg = lambda p: np.log(np.clip(p, LO, HI) / (1 - np.clip(p, LO, HI)))
sg = lambda z: 1 / (1 + np.exp(-np.clip(z, -40, 40)))

# ---------------------------------------------------------------- generator
def gen_forest(n, seed, logic=False):
    """Flat arrays over all claims of n trees. parent = -1 for roots; children always have larger depth."""
    rng = np.random.default_rng(seed)
    P, D, A, B, Q, OBS, T, TREE = [], [], [], [], [], [], [], []
    for t in range(n):
        maxd = rng.integers(1, 5)                      # subtree depth 1..4 below the root
        stack, start = [(-1, 0)], len(P)
        while stack:
            par, d = stack.pop()
            i = len(P); P.append(par); D.append(d); TREE.append(t)
            if par < 0: a = b = 0.5
            elif logic:
                hi_, lo_ = rng.uniform(.9, .99), rng.uniform(.01, .1)
                a, b = (hi_, lo_) if rng.random() < .6 else (lo_, hi_)
            else:
                while True:
                    u, v = rng.uniform(.01, .99, 2)
                    if abs(u - v) >= .1: break
                a, b = (max(u, v), min(u, v)) if rng.random() < .6 else (min(u, v), max(u, v))
            A.append(a); B.append(b)
            nkids = 0 if d >= maxd or len(P) - start > 30 else rng.choice([0, 1, 2, 3, 4], p=[.15, .3, .3, .15, .1] if d else [0, .3, .35, .2, .15])
            leaf = nkids == 0
            if logic: q = rng.uniform(.95, .99) if leaf else .5
            else: q = rng.uniform(.55, .95) if rng.random() < (.9 if leaf else .5) else .5
            Q.append(q)
            for _ in range(nkids): stack.append((i, d + 1))
    P, D, A, B, Q, TREE = map(np.array, (P, D, A, B, Q, TREE))
    N = len(P)
    # sample truths and observations top-down (parents precede children in creation order)
    T = np.zeros(N, bool); OBS = np.zeros(N, bool); M = np.zeros(N)
    for i in range(N):
        if P[i] < 0: M[i] = .5; T[i] = rng.random() < .5
        else:
            M[i] = A[i] * M[P[i]] + B[i] * (1 - M[P[i]])
            T[i] = rng.random() < (A[i] if T[P[i]] else B[i])
        OBS[i] = rng.random() < (Q[i] if T[i] else 1 - Q[i])
    lT = np.where(OBS, Q, 1 - Q); lF = np.where(OBS, 1 - Q, Q)        # P(obs | X), P(obs | not X)
    base = M * lT / (M * lT + (1 - M) * lF)
    # exact upward pass: lam[i] = (P(sub-evidence | X), P(sub-evidence | not X)), normalised per node
    lamT, lamF = lT.copy(), lF.copy()
    for i in range(N - 1, -1, -1):                     # children were created after parents
        if P[i] >= 0:
            p = P[i]
            mT = A[i] * lamT[i] + (1 - A[i]) * lamF[i]   # P(child-sub-evidence | parent true)
            mF = B[i] * lamT[i] + (1 - B[i]) * lamF[i]
            lamT[p] *= mT; lamF[p] *= mF
            z = lamT[p] + lamF[p]; lamT[p] /= z; lamF[p] /= z
    exact = M * lamT / (M * lamT + (1 - M) * lamF)
    nk = np.bincount(P[P >= 0], minlength=N)
    sub = np.zeros(N, int)                             # depth of each claim's own subtree
    for i in range(N - 1, -1, -1):
        if P[i] >= 0: sub[P[i]] = max(sub[P[i]], sub[i] + 1)
    sign = np.where(A > B, 1.0, -1.0); s = np.abs(A - B)
    return dict(parent=P, depth=D, sign=sign, s=s, a=A, b=B, base=base, exact=exact, truth=T, nk=nk, sub=sub, tree=TREE,
                symmetric=np.abs((A + B) - 1) < .25)

def noisy(F, seed, sb=0.7, ss=0.15):
    rng = np.random.default_rng(seed); G = dict(F)
    G["base"] = sg(lg(F["base"]) + rng.normal(0, sb, len(F["base"])))
    G["s"] = np.clip(F["s"] + rng.normal(0, ss, len(F["s"])), 0, 1)
    return G

# ---------------------------------------------------------------- rules: term(child) summed per parent, then combine
def propagate(F, term, combine):
    """Bottom-up, level by level. term(c, s, sign, cbase) -> (n_child, m) array; combine(base, sums (n, m)) -> credence."""
    cred = F["base"].copy(); par = F["parent"]
    for d in range(F["depth"].max(), 0, -1):
        ch = np.where(F["depth"] == d)[0]
        if not len(ch): continue
        tm = term(cred[ch], F["s"][ch], F["sign"][ch], F["base"][ch])
        sums = np.zeros((len(cred), tm.shape[1])); np.add.at(sums, par[ch], tm)
        ps = np.unique(par[ch])
        cred[ps] = combine(F["base"][ps], sums[ps])
    return cred

clampB = lambda b: np.clip(b, .01, .99)
def sides(e, sign, p=1.0):            # sums of e^p on the support side and on the attack side
    e = np.maximum(e, 0) ** p; return np.stack([np.where(sign > 0, e, 0), np.where(sign < 0, e, 0)], 1)
def pn(x, p): return x ** (1 / p)

LAYERS = {
    "dfquad": (lambda c, s, sg_, cb: np.stack([np.where(sg_ > 0, np.log1p(-np.clip(s * c, 0, 1 - 1e-12)), 0),
                                              np.where(sg_ < 0, np.log1p(-np.clip(s * c, 0, 1 - 1e-12)), 0)], 1),
               lambda b, S: (lambda b, es, ea: np.where(es >= ea, b + (1 - b) * (es - ea), b - b * (ea - es)))(
                   clampB(b), 1 - np.exp(S[:, 0]), 1 - np.exp(S[:, 1]))),
    "wlo": (lambda c, s, sg_, cb: sides(np.clip(s * c, 0, 1) ** 1.3, sg_, 2),
            lambda b, S: sg(lg(clampB(b)) + 2.4 * (pn(S[:, 0], 2) - pn(S[:, 1], 2)))),
    "jnb": (lambda c, s, sg_, cb: sides(np.log(c * ((1 + np.minimum(s, .8)) / (1 - np.minimum(s, .8))) ** .7 + (1 - c)), sg_, 2),
            lambda b, S: sg(lg(clampB(b)) + pn(S[:, 0], 2) - pn(S[:, 1], 2))),
    "woe": (lambda c, s, sg_, cb: sides(-np.log(1 - np.minimum(s * c, .7)), sg_, 2),
            lambda b, S: sg(lg(clampB(b)) + 1.2 * (pn(S[:, 0], 2) - pn(S[:, 1], 2)))),
    "euler": (lambda c, s, sg_, cb: (sg_ * s * c)[:, None],
              lambda b, S: 1 - (1 - b * b) / (1 + b * np.exp(np.clip(S[:, 0], -40, 40)))),
    "qe": (lambda c, s, sg_, cb: (sg_ * s * c)[:, None],
           lambda b, S: (lambda e: b - b * (np.maximum(-e, 0) ** 2 / (1 + np.maximum(-e, 0) ** 2)) + (1 - b) * (np.maximum(e, 0) ** 2 / (1 + np.maximum(e, 0) ** 2)))(S[:, 0])),
    "mlp": (lambda c, s, sg_, cb: (sg_ * s * c)[:, None],
            lambda b, S: sg(lg(clampB(b)) + S[:, 0])),
}

def consensus(F):
    return sg(np.mean([lg(propagate(F, *LAYERS[k])) for k in ("wlo", "jnb", "woe")], 0))

# Parameter-free candidate derived from the generator under the simplest assumptions (symmetric relation a = .5+s/2,
# b = .5-s/2, child marginal .5): the exact message is log((1 + s(2c-1)) / (1 - s(2c-1))) = 2 atanh(s(2c-1)).
def bp_sym_term(c, s, sg_, cb): return (sg_ * 2 * np.arctanh(np.clip(s * (2 * c - 1), -.999, .999)))[:, None]
def logodds_combine(b, S): return sg(lg(b) + S[:, 0])

# Two-sided candidate: the rule is ALSO told the relation's shape, i.e. how much the parent moves if the argument is
# true AND if it is false (equivalently (a, b)); child marginal still assumed .5. Measures what asking the judge for that
# second number would buy. term = log((a c + (1-a)(1-c)) / (b c + (1-b)(1-c))).
def two_sided(F):
    a, b = F["a"], F["b"]; cred = F["base"].copy(); par = F["parent"]
    for d in range(F["depth"].max(), 0, -1):
        ch = np.where(F["depth"] == d)[0]
        c = cred[ch]; t = np.log((a[ch] * c + (1 - a[ch]) * (1 - c)) / (b[ch] * c + (1 - b[ch]) * (1 - c)))
        S = np.zeros(len(cred)); np.add.at(S, par[ch], t); ps = np.unique(par[ch]); cred[ps] = sg(lg(F["base"][ps]) + S[ps])
    return cred

# Tunable incremental family (the "sigmoid" generalised). Per child:
#   x = k * (logit c - beta * logit cbase)          k: steepness of the sigmoid applied to the child's credence;
#   u = tanh(x / 2) = 2 sigmoid(x) - 1               beta: how much of the child's own base to discount (double counting)
#   u' = u if u > 0 else eta * u                     eta: weight of a child BELOW neutral (0: a doubted argument is inert)
#   term = sign * (w_sup or w_att) * 2 atanh(s^gamma * u')
# combine: sigmoid(alpha * logit(base) + sum).
FAM = ["alpha", "w_sup", "w_att", "log_gamma", "log_k", "beta", "eta"]
FAM0 = dict(alpha=1, w_sup=1, w_att=1, log_gamma=0, log_k=0, beta=0, eta=1)
def fam(th):
    def term(c, s, sg_, cb):
        u = np.tanh(math.exp(th["log_k"]) * (lg(c) - th["beta"] * lg(cb)) / 2)
        u = np.where(u > 0, u, th["eta"] * u)
        w = np.where(sg_ > 0, th["w_sup"], th["w_att"])
        return (sg_ * w * 2 * np.arctanh(np.clip(s ** math.exp(th["log_gamma"]) * u, -.999, .999)))[:, None]
    return term, (lambda b, S: sg(th["alpha"] * lg(b) + S[:, 0]))

def ce(pred, target):       # cross-entropy against a soft target (exact posterior) or 0/1 truth
    p = np.clip(pred, LO, HI); return -(target * np.log(p) + (1 - target) * np.log(1 - p))

def fit_fam(F, fixed=None):
    fixed = fixed or {}; free = [k for k in FAM if k not in fixed]; m = F["nk"] > 0
    def full(v): th = dict(FAM0); th.update(fixed); th.update(zip(free, v)); return th
    def loss(v): return ce(propagate(F, *fam(full(v)))[m], F["exact"][m]).mean() - ce(F["exact"][m], F["exact"][m]).mean()
    r = minimize(loss, [FAM0[k] for k in free], method="Nelder-Mead", options=dict(maxiter=4000, xatol=1e-4, fatol=1e-7))
    r = minimize(loss, r.x, method="L-BFGS-B")
    return full(r.x)

# ---------------------------------------------------------------- metrics
def metrics(F, pred, mask):
    e, t, b = F["exact"][mask], F["truth"][mask].astype(float), F["base"][mask]
    lp, le, lb = ce(pred[mask], t).mean(), ce(e, t).mean(), ce(b, t).mean()
    return dict(n=int(mask.sum()), kl=float((ce(pred[mask], e) - ce(e, e)).mean()),
                mae_logit=float(np.abs(lg(pred[mask]) - lg(e)).mean()),
                captured=float((lb - lp) / (lb - le)) if lb > le else float("nan"))

def table(title, F, rules):
    interior = F["nk"] > 0
    slices = {"all argued": interior, "root": interior & (F["parent"] < 0),
              "1 level": interior & (F["sub"] == 1), "2 levels": interior & (F["sub"] == 2), "3-4 levels": interior & (F["sub"] >= 3)}
    print(f"\n=== {title}: {int(interior.sum())} argued claims in {F['tree'].max() + 1} trees ===")
    print("  captured = share of the argument information recovered: (logloss(base only) - logloss(rule)) / (logloss(base only) - logloss(exact))")
    print(f"  {'rule':24}" + "".join(f"{k:>24}" for k in slices))
    print(f"  {'':24}" + "".join(f"{'captured  KL   |dlogit|':>24}" for _ in slices))
    out = {}
    for name, pred in rules.items():
        row = {k: metrics(F, pred, m) for k, m in slices.items()}; out[name] = row
        print(f"  {name:24}" + "".join(f"{r['captured']:9.2f} {r['kl']:6.3f} {r['mae_logit']:6.2f}" for r in row.values()))
    return out

def run_rules(F, th):
    R = {"base only (no arguments)": F["base"]}
    for k in LAYERS: R[k] = propagate(F, *LAYERS[k])
    R["consensus(wlo,jnb,woe)"] = consensus(F)
    R["bp-sym (parameter-free)"] = propagate(F, bp_sym_term, logodds_combine)
    R["two-sided (knows a, b)"] = two_sided(F)
    for k, t in th.items(): R[k] = propagate(F, *fam(t))
    return R

def selftest():
    """The exact target equals brute-force enumeration of P(X | evidence in X's subtree) on small trees."""
    import itertools
    rng = np.random.default_rng(7)
    for _ in range(200):     # independent tiny trees, enumerated directly
        n = rng.integers(2, 8); par = [-1] + [int(rng.integers(0, i)) for i in range(1, n)]
        a, b = rng.uniform(.01, .99, n), rng.uniform(.01, .99, n); q = rng.uniform(.5, .95, n); obs = rng.random(n) < .5
        def joint(x):
            pr = 1.0
            for i in range(n):
                pc = .5 if par[i] < 0 else (a[i] if x[par[i]] else b[i])
                pr *= pc if x[i] else 1 - pc
                pr *= (q[i] if x[i] else 1 - q[i]) if obs[i] else (1 - q[i] if x[i] else q[i])
            return pr
        for X in range(n):
            subtree = {X} | {i for i in range(n) if any(j == X for j in _anc(par, i))}
            # P(X | evidence in subtree) = sum over worlds, with evidence outside the subtree marginalised (factor 1)
            num = den = 0.0
            for x in itertools.product([0, 1], repeat=n):
                pr = 1.0
                for i in range(n):
                    pc = .5 if par[i] < 0 else (a[i] if x[par[i]] else b[i])
                    pr *= pc if x[i] else 1 - pc
                    if i in subtree: pr *= (q[i] if x[i] else 1 - q[i]) if obs[i] else (1 - q[i] if x[i] else q[i])
                den += pr; num += pr * x[X]
            # the same via the upward pass
            M = np.zeros(n); lamT = np.where(obs, q, 1 - q); lamF = np.where(obs, 1 - q, q)
            for i in range(n): M[i] = .5 if par[i] < 0 else a[i] * M[par[i]] + b[i] * (1 - M[par[i]])
            for i in range(n - 1, 0, -1):
                p_ = par[i]; mT = a[i] * lamT[i] + (1 - a[i]) * lamF[i]; mF = b[i] * lamT[i] + (1 - b[i]) * lamF[i]
                lamT[p_] *= mT; lamF[p_] *= mF
            up = M[X] * lamT[X] / (M[X] * lamT[X] + (1 - M[X]) * lamF[X])
            assert abs(up - num / den) < 1e-9, (n, X, up, num / den)
    print("selftest ok: upward pass == brute-force enumeration on 200 random trees")

def _anc(par, i):
    while par[i] >= 0: i = par[i]; yield i

if __name__ == "__main__":
    import sys
    if "--selftest" in sys.argv: selftest(); sys.exit()
    TR, TE, LOGIC = gen_forest(2000, 1), gen_forest(2000, 2), gen_forest(1000, 3, logic=True)
    TRn, TEn = noisy(TR, 11), noisy(TE, 12)
    th = {"tuned family": fit_fam(TR),
          "tuned, k only (sigmoid)": fit_fam(TR, fixed={k: FAM0[k] for k in FAM if k != "log_k"}),
          "tuned, no child-base": fit_fam(TR, fixed={"beta": 0.0}),
          "tuned on noisy inputs": fit_fam(TRn)}
    for k, t in th.items():
        print(f"{k}: " + ", ".join(f"{p}={(math.exp(v) if p.startswith('log_') else v):.3f}".replace("log_", "") for p, v in t.items()))
    res = {"exact inputs": table("EXACT inputs (held-out trees)", TE, run_rules(TE, th)),
           "noisy inputs": table("NOISY inputs (base logit +N(0,.7), strength +N(0,.15))", TEn, run_rules(TEn, th)),
           "near-logic": table("NEAR-LOGIC slice (relations |a-b| >= .8, near-certain leaves)", LOGIC, run_rules(LOGIC, th))}
    sym = TE["symmetric"]
    print("\nBy relation shape (exact inputs, claims whose relations are all symmetric vs all argument-style):")
    allsym = np.ones(len(TE["parent"]), bool); allarg = np.ones(len(TE["parent"]), bool)
    ch = TE["parent"] >= 0
    np.logical_and.at(allsym, TE["parent"][ch], sym[ch]); np.logical_and.at(allarg, TE["parent"][ch], ~sym[ch])
    R = run_rules(TE, th)
    for name in ("dfquad", "consensus(wlo,jnb,woe)", "bp-sym (parameter-free)", "tuned family", "two-sided (knows a, b)"):
        a_ = metrics(TE, R[name], (TE["nk"] > 0) & allsym); b_ = metrics(TE, R[name], (TE["nk"] > 0) & allarg)
        print(f"  {name:24} symmetric (n={a_['n']}) captured {a_['captured']:.2f}   argument-style (n={b_['n']}) captured {b_['captured']:.2f}")
    json.dump({"fitted": th, "results": res}, open(HERE / "tier1_results.json", "w"), indent=1)
