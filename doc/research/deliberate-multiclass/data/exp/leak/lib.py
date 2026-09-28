"""Shared loading + metrics for the prior-leakage experiment (computenet-1ow0x)."""
import json, math, random, itertools, statistics
from pathlib import Path
S = Path(__file__).resolve().parents[2]
PILOT = S / "pilot"
GOLDS = {
    "majority": S / "verify/gold/testset_llm_majority_ruleout.json",
    "strict": PILOT / "testset_llm.json",
    "original": PILOT / "testset.json",
}
RANK = {"--": 0, "-": 1, "0": 2, "+": 3, "++": 4}

def logit(p, eps=0.01):
    p = min(1 - eps, max(eps, p)); return math.log(p / (1 - p))

def mean(xs):
    xs = list(xs); return sum(xs) / len(xs) if xs else float("nan")

def pearson(x, y):
    if len(x) < 3: return float("nan")
    mx, my = mean(x), mean(y)
    sx = sum((a - mx) ** 2 for a in x) ** .5; sy = sum((b - my) ** 2 for b in y) ** .5
    return sum((a - mx) * (b - my) for a, b in zip(x, y)) / (sx * sy) if sx and sy else float("nan")

def kappa(pred, gold):
    n = len(pred); po = sum(p == g for p, g in zip(pred, gold)) / n
    p1, g1 = sum(pred) / n, sum(gold) / n; pe = p1 * g1 + (1 - p1) * (1 - g1)
    return (po - pe) / (1 - pe) if pe < 1 else float("nan")

def auc(scores, labels):
    pos = [s for s, l in zip(scores, labels) if l]; neg = [s for s, l in zip(scores, labels) if not l]
    if not pos or not neg: return float("nan")
    return mean((p > n) + 0.5 * (p == n) for p in pos for n in neg)

def load_gold(name):
    ts = json.loads(GOLDS[name].read_text())
    refs, contested, q_of, classes = {}, set(), {}, {}
    for q in ts["questions"]:
        classes[q["id"]] = list(q["classes"])
        for c in q["claims"]:
            assert c["id"] not in q_of
            q_of[c["id"]] = q["id"]
            for k, v in c["ref"].items(): refs[(c["id"], k)] = v
            for k in c.get("contested", {}): contested.add((c["id"], k))
    return dict(refs=refs, contested=contested, q_of=q_of, classes=classes, ts=ts)

def load_prior():
    pr = {}
    for l in (PILOT / "responses.jsonl").open():
        r = json.loads(l)
        if r["rid"].startswith("PRIOR|"):
            qid = r["rid"].split("|")[1]
            for k, v in r["response"]["answers"].items():
                if k.startswith("prior_noul|"): pr.setdefault((qid, k.split("|")[1]), []).append(v["noul"])
    return {k: mean(v) for k, v in pr.items()}

def raw_value(shape, a):
    if a["type"] == "noul":
        return 1 - a["noul"] if shape.startswith("elim") else a["noul"]
    return a["score"] / 4

def collect(paths, arm_prefix, shapes, claim_filter=None):
    """{shape: {(claim, class): mean raw value over replicates}} from responses whose rid starts with arm_prefix."""
    reps = {}
    for p in paths:
        for l in Path(p).open():
            r = json.loads(l); parts = r["rid"].split("|")
            if parts[0] != arm_prefix: continue
            cl = parts[1]
            for k, a in r["response"]["answers"].items():
                s, _, cid = k.partition("|")
                if s in shapes: reps.setdefault(s, {}).setdefault((cl, cid), []).append(raw_value(s, a))
    return {s: {k: mean(v) for k, v in d.items()} for s, d in reps.items()}

def metrics(sig, g, prior, *, kind, tie=0.02, raw_rule=None, boot=400, strong=("--", "++")):
    """sig: {(claim,class): signed signal, higher = more favourable}.
    kind 'prob' -> signal in [0,1]; 'delta' -> 0 = no bearing (scale-free FMR).
    raw_rule: optional fn(value)->ruled_out for the thresholded kappa; else threshold picked leave-one-question-out."""
    refs, q_of = g["refs"], g["q_of"]
    keys = [k for k in sig if k in refs and k not in g["contested"]]
    # POA
    hits = n = 0
    for cl in {k[0] for k in keys}:
        ks = [k for k in keys if k[0] == cl]
        for x, y in itertools.combinations(ks, 2):
            rx, ry = RANK[refs[x]], RANK[refs[y]]
            if rx == ry: continue
            d = sig[x] - sig[y]; n += 1
            hits += 0.5 if abs(d) < tie else float((d > 0) == (rx > ry))
    poa = hits / n
    # leakage on gold-neutral cells
    zero = [k for k in keys if refs[k] == "0"]
    px = [prior[(q_of[k[0]], k[1])] for k in zero]; py = [sig[k] for k in zero]
    leak = pearson(px, py)
    rng = random.Random(0); bs = []
    for _ in range(boot):
        idx = [rng.randrange(len(zero)) for _ in zero]
        bs.append(pearson([px[i] for i in idx], [py[i] for i in idx]))
    bs = sorted(b for b in bs if b == b); lo, hi = bs[int(.05 * len(bs))], bs[int(.95 * len(bs)) - 1]
    # rule-out graded AUC + kappa
    lab = [refs[k] == "--" for k in keys]
    ro_auc = auc([-sig[k] for k in keys], lab)
    if raw_rule:
        kap = kappa([raw_rule(sig[k]) for k in keys], lab)
    else:  # LOQO threshold maximising kappa on the other questions
        pred = []
        for k in keys:
            other = [j for j in keys if q_of[j[0]] != q_of[k[0]]]
            cands = sorted({sig[j] for j in other})
            best = max(cands, key=lambda t: (kappa([sig[j] <= t for j in other], [refs[j] == "--" for j in other]), -t))
            pred.append(sig[k] <= best)
        kap = kappa(pred, lab)
    # neutral false-move rate
    if kind == "prob":
        fmr = float("nan")
    else:
        mv = [abs(sig[k]) for k in keys if refs[k] in strong]
        thr = 0.5 * statistics.median(mv)  # "half a strong move" ~ one level of five
        fmr = mean(abs(sig[k]) >= thr for k in zero)
    return dict(leak=leak, leak_ci=(lo, hi), n0=len(zero), poa=poa, auc=ro_auc, kappa=kap, fmr=fmr)

# ---------------------------------------------------------------- corrections
def loo_stat(L, g, fn):
    """per cell: fn over the same class's values on the OTHER claims of the same question."""
    out = {}
    for (cl, c), v in L.items():
        q = g["q_of"][cl]
        others = [L[(o, c)] for (o, cc) in L if cc == c and o != cl and g["q_of"][o] == q]
        out[(cl, c)] = fn(others)
    return out

BETAS = {}
def corrections(raw, g, prior, shape, placebo=None):
    """raw: {(claim,class): value in [0,1]}. Returns {name: (signal, kind)}."""
    L = {k: logit(v) for k, v in raw.items()}
    lp = {k: logit(prior[(g["q_of"][k[0]], k[1])]) for k in raw}
    out = {"raw": (dict(raw), "prob")}
    if shape not in ("compat", "elim", "cinstr"):
        out["raw-centred"] = ({k: v - 0.5 for k, v in raw.items()}, "delta")
    out["prior-logit b=1"] = ({k: L[k] - lp[k] for k in L}, "delta")
    # beta fitted leave-one-question-out, unsupervised (all cells, no gold)
    sig = {}
    for q in set(g["q_of"].values()):
        ks = [k for k in L if g["q_of"][k[0]] != q]
        mx, my = mean(lp[k] for k in ks), mean(L[k] for k in ks)
        b = sum((lp[k] - mx) * (L[k] - my) for k in ks) / sum((lp[k] - mx) ** 2 for k in ks)
        for k in L:
            if g["q_of"][k[0]] == q: sig[k] = L[k] - b * lp[k]
        BETAS.setdefault(shape, []).append(round(b, 2))
    out["prior-logit b=fit"] = (sig, "delta")
    med = loo_stat(L, g, statistics.median)
    out["class-centre (LOO median)"] = ({k: L[k] - med[k] for k in L}, "delta")
    mu = loo_stat(L, g, mean); sd = loo_stat(L, g, lambda xs: statistics.pstdev(xs) or 1.0)
    out["class-z (LOO)"] = ({k: (L[k] - mu[k]) / max(sd[k], 0.25) for k in L}, "delta")
    if shape in ("lik",):
        avg = loo_stat(raw, g, mean)
        out["lik / class-mean"] = ({k: math.log(max(raw[k], .01) / max(avg[k], .01)) for k in raw}, "delta")
    if placebo:
        Lp = {k: logit(v) for k, v in placebo.items()}
        out["placebo-diff (logit)"] = ({k: L[k] - Lp[(g["q_of"][k[0]], k[1])] for k in L}, "delta")
        if shape not in ("compat", "elim", "cinstr"):
            out["placebo-diff (linear)"] = ({k: raw[k] - placebo[(g["q_of"][k[0]], k[1])] for k in raw}, "delta")
    return out
