#!/usr/bin/env python3
"""Tune the one-level credence rule against real answers, WITHOUT Opus's (or Sol's) direct answer as an input.
Run: uv run --with numpy --with scipy python tune.py   (after extract.py). Offline; no model calls.

Constraint (user, 2026-10-03): the rule must stay INCREMENTAL. Every candidate below is
    logit_c = a * logit(pos_c) + (sum_j phi(claim_j)_c) / (1 + N)^rho,   credence = softmax(logit)
where phi reads only claim j's own judgements. A parent cell therefore keeps two running sums (the K-vector sum and
the count N); adding, retracting or revising an argument is one subtract and one add. The only non-incremental model
is FLEX, which uses max/min over claims; it is reported as a reference for what the restriction costs, never as a candidate.

phi(j)_c = gate_j * agree_j * srcw_j * transfer_jc, with
    gate     = sigmoid(s * (p' - theta)) * (omega_out if Jev said OUTSIDE_MY_KNOWLEDGE)    p' = lam*p + (1-lam)*p_cf
    agree    = exp(-kappa * |bA - bB|)          (paraphrase disagreement discounts the claim)
    srcw     = 1 for Opus-proposed claims, w_sol for Sol-proposed
    transfer = w_sup * d+^gamma - w_att * d-^gamma - w_r0 * (r0_c - mean r0) + w_r4 * (r4_c - mean r4)
               d = b_c - mean_c' b  (bearing on c relative to the claim's mean bearing; d+ / d- its parts)
Protocol: train = push-tune (55) + knowledge (50). Repeated 5-fold CV on train gives the out-of-sample estimate used for
every comparison; the fit on all of train is applied ONCE to push held-out (25). Opus mean-of-5 is scored as a reference
line only. Score: Metaculus baseline 100*(ln p'_y - ln 1/K)/ln K, p' = .99p + .01/K (as the earlier reports)."""
import json, math, sys
from pathlib import Path
import numpy as np
from scipy.optimize import minimize

HERE = Path(__file__).resolve().parent
D = json.loads((HERE / "dataset.json").read_text())
Q, J, K = len(D), max(len(d["claims"]) for d in D), max(d["K"] for d in D)

# ---------------------------------------------------------------- tensors (Q x J x K, masked)
def arr(f, shape, fill=0.0):
    a = np.full(shape, fill)
    for i, d in enumerate(D): f(a, i, d)
    return a
kmask = arr(lambda a, i, d: a.__setitem__((i, slice(0, d["K"])), 1), (Q, K)).astype(bool)
jmask = arr(lambda a, i, d: a.__setitem__((i, slice(0, len(d["claims"]))), 1), (Q, J))
def cl(key, kdim=True):
    def f(a, i, d):
        for j, c in enumerate(d["claims"]):
            if kdim: a[i, j, :d["K"]] = c[key]
            else: a[i, j] = float(c[key]) if not isinstance(c[key], str) else float(c[key] == "sol")
    return arr(f, (Q, J, K) if kdim else (Q, J))
bA, bB, r0, r4 = cl("bA"), cl("bB"), cl("r0"), cl("r4")
p, pcf, outside, sol = cl("p", False), cl("pcf", False), cl("outside", False), cl("src", False)
pos = np.clip(arr(lambda a, i, d: a.__setitem__((i, slice(0, d["K"])), d["pos"]), (Q, K), 0.5), .02, .98)
y = np.array([d["y"] for d in D]); Kq = np.array([d["K"] for d in D])
b = (bA + bB) / 2
km = kmask[:, None, :]
def cmean(x): return (x * km).sum(-1, keepdims=True) / Kq[:, None, None]
dev = (b - cmean(b)) * km; dpos, dneg = np.maximum(dev, 0), np.maximum(-dev, 0)
r0c, r4c = (r0 - cmean(r0)) * km, (r4 - cmean(r4)) * km
dis = (np.abs(bA - bB) * km).sum(-1) / Kq[:, None]
Ncl = jmask.sum(1)
lpos = np.log(pos / (1 - pos))

def score_rows(P, idx):
    P = .99 * P + .01 / Kq[idx, None]
    return 100 * (np.log(P[np.arange(len(idx)), y[idx]]) + np.log(Kq[idx])) / np.log(Kq[idx])

def sm(L, idx):
    L = np.where(kmask[idx], L, -np.inf); L = L - L.max(1, keepdims=True); E = np.exp(L); return E / E.sum(1, keepdims=True)

# ---------------------------------------------------------------- the incremental family
sig = lambda x: 1 / (1 + np.exp(-np.clip(x, -30, 30)))
ex = lambda x: float(np.exp(np.clip(x, -30, 30)))
PARAMS = ["log_s", "theta", "lam_l", "omega_l", "w_sup", "w_att", "log_gamma", "w_r0", "w_r4", "log_kappa", "log_wsol", "a", "rho_l"]
INIT = dict(log_s=math.log(4), theta=0.5, lam_l=2.0, omega_l=0.0, w_sup=2.0, w_att=2.0, log_gamma=0.0, w_r0=0.0, w_r4=0.0,
            log_kappa=0.0, log_wsol=0.0, a=0.0, rho_l=-2.0)
# Ablations pin a component to its neutral value. "linear gate" = p itself, no sigmoid.
NEUTRAL = {"sigmoid gate": None, "context-free mix": {"lam_l": 20.0}, "outside discount": {"omega_l": 20.0},
           "support/attack asymmetry": None, "power gamma": {"log_gamma": 0.0}, "rule-out mass": {"w_r0": 0.0},
           "settles mass": {"w_r4": 0.0}, "paraphrase agreement": {"log_kappa": -20.0}, "Sol claim weight": {"log_wsol": 0.0},
           "position prior": {"a": 0.0}, "count damping": {"rho_l": -20.0}}

def unpack(v): return dict(zip(PARAMS, v))

def logits(th, idx, linear_gate=False, tie=False):
    s, lam, om = ex(th["log_s"]), sig(th["lam_l"]), sig(th["omega_l"])
    pp = lam * p[idx] + (1 - lam) * pcf[idx]
    g = pp if linear_gate else sig(s * (pp - th["theta"]))
    g = g * np.where(outside[idx] > 0, om, 1.0) * np.exp(-ex(th["log_kappa"]) * dis[idx])
    g = g * np.where(sol[idx] > 0, ex(th["log_wsol"]), 1.0) * jmask[idx]
    gm = ex(th["log_gamma"])
    wa = th["w_sup"] if tie else th["w_att"]
    tr = th["w_sup"] * (dpos[idx] + 1e-12) ** gm - wa * (dneg[idx] + 1e-12) ** gm - th["w_r0"] * r0c[idx] + th["w_r4"] * r4c[idx]
    S = (g[..., None] * tr).sum(1)
    return th["a"] * lpos[idx] + S / (1 + Ncl[idx, None]) ** sig(th["rho_l"])

def fit_family(idx, fixed=None, linear_gate=False, tie=False, l2=1e-3, restarts=(0,)):
    fixed = fixed or {}; free = [k for k in PARAMS if k not in fixed]
    def full(v): th = dict(INIT); th.update(fixed); th.update(zip(free, v)); return th
    def nll(v):
        P = sm(logits(full(v), idx, linear_gate, tie), idx)
        return -np.log(P[np.arange(len(idx)), y[idx]] + 1e-300).mean() + l2 * float(np.dot(v, v))
    best = None
    for r in restarts:
        x0 = np.array([INIT[k] for k in free]) + np.random.default_rng(r).normal(0, .5 if r else 0, len(free))
        res = minimize(nll, x0, method="L-BFGS-B")
        if best is None or res.fun < best.fun: best = res
    th = full(best.x)
    return th, (lambda jdx: sm(logits(th, jdx, linear_gate, tie), jdx))

# ---------------------------------------------------------------- baselines on the same splits
def fit_uniform(idx): return None, (lambda jdx: sm(np.zeros((len(jdx), K)), jdx))

def fit_modelA(idx):
    th, f = fit_family(idx, fixed={k: v for k, v in INIT.items() if k != "a"} | {"w_sup": 0, "w_att": 0})
    return th, f

# shipped-style semantics (analyze2.P with a uniform prior: woe + mlp layers, geometric pool), Opus claims, CV-chosen m
opus_m = (1 - sol) * jmask
hi, lo = np.where(km, b, -np.inf).max(-1), np.where(km, b, np.inf).min(-1)
kap = np.where(km, (b - lo[..., None]) / np.maximum(hi - lo, 1e-9)[..., None], 1)
energy = (hi - lo) * p * opus_m * (hi - lo > 1e-9)          # (hi-lo)/2 on the [-1,1] scale = hi-lo on [0,1]
pen_woe = 1.2 * np.sqrt(((-np.log(1 - np.minimum(energy, .7)))[..., None] * (1 - kap)) ** 2 * km).sum(1)
pen_mlp = 1.0 * (energy[..., None] * (1 - kap) * km).sum(1)
def shipped(m, jdx):
    lw, lm = np.log(sm(-m * pen_woe[jdx], jdx) + 1e-300), np.log(sm(-m * pen_mlp[jdx], jdx) + 1e-300)
    return sm((lw + lm) / 2, jdx)
M_GRID = [0, .25, .5, .75, 1, 1.5, 2, 3, 4, 6, 8, 12, 16, 24]
def fit_shipped(idx):
    m = max(M_GRID, key=lambda m: score_rows(shipped(m, idx), idx).mean())
    return {"m": m}, (lambda jdx: shipped(m, jdx))

# FLEX: non-incremental reference (learn.py's argument aggregates incl. max/min, plus the position prior), conditional logit
def flex_feats():
    w = p[..., None] * jmask[..., None]
    F = [lpos, (w * dev).sum(1), (w * dpos).sum(1), (w * dneg).sum(1),
         np.where(jmask[..., None] > 0, w * dev, -9).max(1), np.where(jmask[..., None] > 0, w * dev, 9).min(1),
         (w * (b >= b.max(-1, keepdims=True) - 1e-12) * km).sum(1), (w * (b <= .25)).sum(1), (w * r0c).sum(1), (w * r4c).sum(1)]
    F = np.stack(F, -1); F[~kmask] = 0; return F
FX = flex_feats()
def fit_flex(idx, lam=0.1):
    mu, sd = FX[idx][kmask[idx]].mean(0), FX[idx][kmask[idx]].std(0) + 1e-9
    Z = (FX - mu) / sd
    def nll(w):
        P = sm(Z[idx] @ w, idx); return -np.log(P[np.arange(len(idx)), y[idx]] + 1e-300).mean() + lam * w @ w / len(idx)
    w = minimize(nll, np.zeros(FX.shape[-1]), method="L-BFGS-B").x
    return w, (lambda jdx: sm(Z[jdx] @ w, jdx))

# LININC: still INCREMENTAL. phi(j)_c = w . basis(claim j, class c), a learned linear function of the claim's own
# judgements (with interactions), so the parent keeps the same two running sums. Convex for a fixed rho.
def basis():
    pp, P2 = p[..., None], pcf[..., None]
    o, sl, ds = outside[..., None], sol[..., None], dis[..., None]
    B = [dev, pp * dev, P2 * dev, pp * dpos, pp * dneg, pp ** 2 * dev, (1 - o) * pp * dev, o * dev, sl * pp * dev,
         ds * pp * dev, pp * r0c, pp * r4c, r0c, r4c, pp * dev * np.abs(dev)]
    return np.stack([x * jmask[..., None] * km for x in B], -1)        # Q x J x K x F
BAS = basis()
def fit_lininc(idx, rho=0.5, lam=3.0):
    Z = BAS.sum(1) / (1 + Ncl[:, None, None]) ** rho                   # Q x K x F: the parent's running sum, normalised
    Z = np.concatenate([Z, lpos[..., None]], -1)
    sd = Z[idx][kmask[idx]].std(0) + 1e-9; Z = Z / sd
    def nll(w):
        P = sm(Z[idx] @ w, idx); return -np.log(P[np.arange(len(idx)), y[idx]] + 1e-300).mean() + lam * w @ w / len(idx)
    w = minimize(nll, np.zeros(Z.shape[-1]), method="L-BFGS-B").x
    return w, (lambda jdx: sm(Z[jdx] @ w, jdx))

# GBT: non-incremental, unrestricted probe. Gradient-boosted trees on per-(question, option) features (FLEX aggregates
# + the summed LININC basis + K), trained as 'is this the answer', renormalised per question with a fitted temperature.
def fit_gbt(idx):
    from sklearn.ensemble import HistGradientBoostingClassifier
    Fq = np.concatenate([FX, BAS.sum(1), np.repeat(Kq[:, None, None], K, 1)], -1)
    rows = [(i, c) for i in idx for c in range(Kq[i])]
    X = np.array([Fq[i, c] for i, c in rows]); t = np.array([int(c == y[i]) for i, c in rows])
    m = HistGradientBoostingClassifier(max_depth=3, learning_rate=.05, max_iter=150, min_samples_leaf=20, l2_regularization=1.0, random_state=0).fit(X, t)
    def pred(jdx):
        L = np.zeros((len(jdx), K))
        for r, i in enumerate(jdx): L[r, :Kq[i]] = m.predict_proba(Fq[i, :Kq[i]])[:, 1]
        return sm(np.log(np.maximum(L, 1e-6)), jdx)
    return m, pred

# Opus mean-of-5 reference (scored, never an input)
def opus_ref():
    sys.path.insert(0, str(HERE.parent / "data/exp/push")); sys.path.insert(0, str(HERE.parent / "data/exp/e2e2"))
    import analyze2 as A2
    llm = {}
    for f in ("push/cache/llm.jsonl", "e2e2/cache/llm.jsonl", "e2e/cache/llm.jsonl"):
        for l in (HERE.parent / "data/exp" / f).open(): r = json.loads(l); llm[r["key"]] = r
    P = np.zeros((Q, K))
    for i, d in enumerate(D):
        ds = [llm[k]["parsed"] for s in range(5) if (k := f"opus|direct|{d['id']}|{s}") in llm]
        P[i, :d["K"]] = [np.mean([x[c] for x in ds]) for c in d["classes"]]
    return P / P.sum(1, keepdims=True)
OPUS = opus_ref()

# ---------------------------------------------------------------- protocol
TRAIN = np.array([i for i, d in enumerate(D) if d["split"] == "tune"]); HELD = np.array([i for i, d in enumerate(D) if d["split"] == "heldout"])
SETS = {"push-tune": np.array([i for i in TRAIN if D[i]["set"] == "push"]), "know": np.array([i for i in TRAIN if D[i]["set"] == "know"])}

def cv(fitter, reps=4, k=5):
    oof = np.zeros((reps, Q))
    for r in range(reps):
        perm = np.random.default_rng(100 + r).permutation(TRAIN); folds = np.array_split(perm, k)
        for f in folds:
            tr = np.setdiff1d(TRAIN, f); _, pred = fitter(tr); oof[r, f] = score_rows(pred(f), f)
    return oof.mean(0)          # per-question out-of-fold score, averaged over repeats

def boot(x, n=4000, seed=1):
    rng = np.random.default_rng(seed); m = rng.choice(x, (n, len(x))).mean(1)
    return x.mean(), np.percentile(m, 2.5), np.percentile(m, 97.5)

def line(name, s, ref=None):
    out = f"  {name:44}"
    for sn, ix in SETS.items():
        mu, lo_, hi_ = boot(s[ix]); out += f"  {sn} {mu:6.1f} [{lo_:5.1f},{hi_:5.1f}]"
    mu, lo_, hi_ = boot(s[TRAIN]); out += f"  pooled {mu:6.1f} [{lo_:5.1f},{hi_:5.1f}]"
    if ref is not None:
        mu, lo_, hi_ = boot(s[TRAIN] - ref[TRAIN]); out += f"  Δ vs shipped {mu:+5.1f} [{lo_:+5.1f},{hi_:+5.1f}]"
    print(out, flush=True)

if __name__ == "__main__":
    opus = score_rows(OPUS, np.arange(Q))
    print(f"data: train {len(TRAIN)} (push-tune {len(SETS['push-tune'])}, know {len(SETS['know'])}), held-out {len(HELD)}; "
          f"J<={J}, K<={K}\nCV: 4x repeated 5-fold on train; Metaculus baseline, uniform = 0; 95% bootstrap CI over questions\n")
    print("REFERENCE (not a candidate, not an input)"); line("Opus mean of 5", opus)
    print("\nBASELINES")
    S = {}
    S["uniform"] = cv(fit_uniform); line("uniform", S["uniform"])
    S["modelA"] = cv(fit_modelA); line("model A (Jev position plausibility, fitted T)", S["modelA"])
    S["shipped"] = cv(fit_shipped); line("shipped semantics, args alone (CV m)", S["shipped"])
    print("\nINCREMENTAL FAMILY (all components free)")
    S["full"] = cv(lambda ix: fit_family(ix)); line("full family", S["full"], S["shipped"])
    S["tie"] = cv(lambda ix: fit_family(ix, tie=True)); line("  support = attack weight", S["tie"], S["shipped"])
    S["lin"] = cv(lambda ix: fit_family(ix, linear_gate=True)); line("  linear gate (no sigmoid)", S["lin"], S["shipped"])
    print("\nABLATIONS (one component pinned to neutral; Δ is vs shipped)")
    for name, fx in NEUTRAL.items():
        if fx is None: continue
        S[name] = cv(lambda ix, fx=fx: fit_family(ix, fixed=fx)); line(f"  without {name}", S[name], S["shipped"])
    print("\nSIGMOID STEEPNESS PROFILE (s pinned, everything else refit)")
    for s_ in (0.5, 1, 2, 4, 8, 16, 32, 64):
        line(f"  s = {s_}", cv(lambda ix: fit_family(ix, fixed={"log_s": math.log(s_)})), S["shipped"])
    print("\nINCREMENTAL, LEARNED per-claim contribution (LININC; rho x ridge grid)")
    for rho in (0, .5, 1):
        for lam in (.3, 3, 30):
            S[f"lininc{rho},{lam}"] = cv(lambda ix: fit_lininc(ix, rho, lam)); line(f"  LININC rho={rho} ridge={lam}", S[f"lininc{rho},{lam}"], S["shipped"])
    print("\nNON-INCREMENTAL: what is possible (GBT); FLEX below measures what the restriction costs")
    S["gbt"] = cv(fit_gbt, reps=2); line("GBT (trees, unrestricted)", S["gbt"], S["shipped"])
    print("\nNON-INCREMENTAL REFERENCE (max/min aggregates; what the restriction costs)")
    S["flex"] = cv(fit_flex); line("FLEX conditional logit", S["flex"], S["shipped"])

    print("\nFULL FIT ON TRAIN, applied once to push held-out (n=25)")
    th, f = fit_family(TRAIN, restarts=(0, 1, 2, 3))
    print("  fitted:", {k: round(float(v), 3) for k, v in th.items()},
          f"-> s={math.exp(th['log_s']):.2f} lam={sig(th['lam_l']):.2f} omega={sig(th['omega_l']):.2f} gamma={math.exp(th['log_gamma']):.2f} "
          f"kappa={math.exp(th['log_kappa']):.2f} w_sol={math.exp(th['log_wsol']):.2f} rho={sig(th['rho_l']):.2f}")
    for name, fitter in (("uniform", fit_uniform), ("model A", fit_modelA), ("shipped semantics", fit_shipped),
                         ("incremental family", lambda ix: (th, f)), ("LININC rho=.5 ridge=3", fit_lininc),
                         ("FLEX (non-incremental)", fit_flex), ("GBT (non-incremental)", fit_gbt)):
        _, pr = fitter(TRAIN); s = score_rows(pr(HELD), HELD); mu, lo_, hi_ = boot(s)
        acc = (pr(HELD).argmax(1) == y[HELD]).mean()
        print(f"  {name:28} held-out {mu:6.1f} [{lo_:5.1f},{hi_:5.1f}]  top-1 {acc:.2f}")
    s = opus[HELD]; mu, lo_, hi_ = boot(s); print(f"  {'Opus mean of 5 (reference)':28} held-out {mu:6.1f} [{lo_:5.1f},{hi_:5.1f}]  top-1 {(OPUS[HELD].argmax(1) == y[HELD]).mean():.2f}")
    json.dump({k: float(v) for k, v in th.items()}, open(HERE / "fitted.json", "w"), indent=1)
