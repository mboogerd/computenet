#!/usr/bin/env python3
"""Arms x metrics for the e2e proxy (computenet-1ow0x). Reads cache/llm.jsonl and cache/jev.jsonl.

Bearing -> (strength, kappa) mapping, per claim i (documented, fixed before looking at scores):
  v_ik  = Jev `bear` expected score / 4, mean of paraphrases A and B      (0 = rules out .. 1 = settles)
  s_ik  = 2 v_ik - 1  in [-1, 1]                                          (signed bearing)
  strength_i = (max_k s_ik - min_k s_ik) / 2                              (how much the claim discriminates)
  kappa_ik   = (s_ik - min_k s_ik) / (max_k s_ik - min_k s_ik)            (1 = most compatible class, 0 = least)
  credence_i = Jev plausibility (score/4; OUTSIDE_MY_KNOWLEDGE -> 0.5, as JevJudge)
  energy_i   = strength_i * credence_i
At K=2 a pure pro claim (s = +a, -a) gives strength a, kappa (1, 0): the binary pro edge of strength a.
Combination: closed-world log-linear elimination, score_k = log pi_k - k * || w(e_i)(1 - kappa_ik) ||_p, softmax.
  woe: w = -ln(1 - min(e, .7)), p=2, k=1.2      mlp: w = e, p=1, k=1      wlo: w = e^1.3, p=2, k=2.4
  consensus = log-pool (normalised geometric mean) of woe and mlp. Base pi = uniform (arguments alone).
P' (prior-leakage correction, "subtract Jev's no-claim class prior in logit space"):
  x_ik = logit(v_ik) - beta (logit(jp_k) - mean_j logit(jp_j)), beta=1 (as asked) and beta=.4 (median bear beta fitted in exp/leak part_b, not tuned here),  v'_ik = sigmoid(x_ik); jp_k = Jev prior Noul for class k
  (mean of paraphrases), both clamped to [.02,.98]; then as above.
"""
import json, math, random, statistics, sys
from pathlib import Path
HERE = Path(__file__).resolve().parent
QS = json.loads((HERE / "questions.json").read_text())["questions"]
EPS = 0.01  # every arm: p' = (1-EPS) p + EPS/K before scoring (keeps log score finite for 0-probability answers)

def load(name):
    out = {}
    p = HERE / "cache" / f"{name}.jsonl"
    if p.exists():
        for l in p.open():
            r = json.loads(l); out[r["key"]] = r
    return out
LLM, JEV = load("llm"), load("jev")

def cl(x, lo, hi): return min(max(x, lo), hi)
def logit(p): return math.log(p / (1 - p))
def sig(z): return 1 / (1 + math.exp(-z))
def softmax(sc):
    m = max(sc.values()); z = {k: math.exp(v - m) for k, v in sc.items()}; t = sum(z.values())
    return {k: v / t for k, v in z.items()}
def pnorm(xs, p):
    xs = [x for x in xs if x > 0]
    return sum(x ** p for x in xs) ** (1 / p) if xs else 0.0
def normalise(d):
    t = sum(d.values()); return {k: v / t for k, v in d.items()}

LAYERS = {
    "woe": (lambda e: -math.log(1 - min(max(e, 0), 0.7)), 2, 1.2),
    "mlp": (lambda e: e, 1, 1.0),
    "wlo": (lambda e: cl(e, 0, 1) ** 1.3, 2, 2.4),
}
def LL(claims, pi, layer):
    w, p, k = LAYERS[layer]
    return softmax({c: math.log(pi[c]) - k * pnorm([w(e) * (1 - kap[c]) for e, kap in claims], p) for c in pi})
def logpool(ds):
    ks = ds[0].keys()
    return softmax({k: statistics.mean(math.log(max(d[k], 1e-12)) for d in ds) for k in ks})

def ans(rid): return JEV[rid]["response"]["answers"] if rid in JEV else None

def plaus(a):
    if a["knowledge"]["choice"] == "OUTSIDE_MY_KNOWLEDGE":
        return 0.5
    return cl(a["plausibility"]["score"] / 4, 0, 1)

def jev_prior(q):
    noul = {c: [] for c in q["classes"]}; ch = {c: [] for c in q["classes"]}
    for p in "AB":
        a = ans(f"PRIOR|{q['id']}|{p}")
        if not a: continue
        for c in q["classes"]:
            noul[c].append(a[f"prior_noul|{c}"]["noul"])
            ch[c].append(a["prior_choice"]["probabilities"].get(c, 0.0))
    return ({c: statistics.mean(v) for c, v in noul.items()}, normalise({c: statistics.mean(v) for c, v in ch.items()}))

def claims_of(q, model, correct=None, beta=1.0):
    rec = LLM.get(f"{model}|propose|{q['id']}|0")
    if not rec: return None
    out, raw = [], []
    for i, _ in enumerate(rec["parsed"][:12]):
        pa = ans(f"PL|{model}|{q['id']}|{i}")
        bs = [ans(f"BEAR|{model}|{q['id']}|{i}|{p}") for p in "AB"]
        bs = [b for b in bs if b]
        if not pa or not bs: continue
        v = {c: statistics.mean(b[f"bear|{c}"]["score"] / 4 for b in bs) for c in q["classes"]}
        if correct is not None:
            jp = correct
            L = {c: logit(cl(jp[c], .02, .98)) for c in q["classes"]}; mL = statistics.mean(L.values())
            v = {c: sig(logit(cl(v[c], .02, .98)) - beta * (L[c] - mL)) for c in q["classes"]}
        s = {c: 2 * v[c] - 1 for c in v}
        hi, lo = max(s.values()), min(s.values())
        cred = plaus(pa)
        raw.append((cred, s))
        if hi - lo < 1e-9: continue
        out.append(((hi - lo) / 2 * cred, {c: (s[c] - lo) / (hi - lo) for c in s}))
    return out, raw

def pipeline(q, model, pi=None, correct=None, layer=None, beta=1.0):
    r = claims_of(q, model, correct, beta)
    if r is None: return None
    cls, _ = r
    pi = pi or {c: 1 / len(q["classes"]) for c in q["classes"]}
    if layer: return LL(cls, pi, layer)
    return logpool([LL(cls, pi, "woe"), LL(cls, pi, "mlp")])

def arms_for(q):
    K = len(q["classes"]); uni = {c: 1 / K for c in q["classes"]}
    jp, jchoice = jev_prior(q)
    A = {}
    A["P (Opus claims)"] = pipeline(q, "opus")
    A["P' (Opus, leak-corr)"] = pipeline(q, "opus", correct=jp)
    A["P' b=.4 (Opus, leak-fit beta)"] = pipeline(q, "opus", correct=jp, beta=0.4)
    A["P-sol"] = pipeline(q, "sol")
    A["P'-sol (leak-corr)"] = pipeline(q, "sol", correct=jp)
    A["P'-sol b=.4"] = pipeline(q, "sol", correct=jp, beta=0.4)
    A["P woe only"] = pipeline(q, "opus", layer="woe")
    A["P mlp only"] = pipeline(q, "opus", layer="mlp")
    A["P wlo only"] = pipeline(q, "opus", layer="wlo")
    A["P + Jev prior base"] = pipeline(q, "opus", pi=normalise({c: cl(jp[c], .02, .98) for c in jp}))
    pos = {c: ans(f"POS|{q['id']}|{c}") for c in q["classes"]}
    if all(pos.values()):
        A["model A (softmax T=1)"] = softmax({c: logit(cl(plaus(pos[c]), .001, .999)) for c in q["classes"]})
    A["Jev Choice"] = jchoice
    A["Jev prior Noul (normalised)"] = normalise({c: cl(jp[c], .001, .999) for c in jp})
    ds = [LLM[f"opus|direct|{q['id']}|{s}"]["parsed"] for s in range(5) if f"opus|direct|{q['id']}|{s}" in LLM]
    if ds:
        A["Opus direct"] = ds[0]
        A["Opus mean of 5"] = {c: statistics.mean(d[c] for d in ds) for c in q["classes"]}
        votes = {c: 0.0 for c in q["classes"]}
        for d in ds:
            m = max(d.values()); top = [c for c in d if d[c] == m]
            for c in top: votes[c] += 1 / len(top)
        A["Opus majority of 5 (+0.5 smooth)"] = normalise({c: v + 0.5 for c, v in votes.items()})
    sd = LLM.get(f"sol|direct|{q['id']}|0")
    if sd: A["Sol direct"] = sd["parsed"]
    A["Uniform"] = uni
    return A

def score(d, truth):
    K = len(d); p = {c: (1 - EPS) * v + EPS / K for c, v in d.items()}
    lp = math.log(p[truth])
    mb = 100 * (lp - math.log(1 / K)) / math.log(K)
    brier = sum((p[c] - (c == truth)) ** 2 for c in p)
    m = max(d.values()); top = [c for c in d if abs(d[c] - m) < 1e-12]
    acc = (truth in top) / len(top)
    return {"ln": lp, "mb": mb, "brier": brier, "acc": acc}

def boot(xs, n=10000, seed=1):
    rnd = random.Random(seed); N = len(xs)
    ms = sorted(statistics.mean(xs[rnd.randrange(N)] for _ in range(N)) for _ in range(n))
    return statistics.mean(xs), ms[int(.025 * n)], ms[int(.975 * n)]

def main():
    per = {}
    for q in QS:
        for arm, d in arms_for(q).items():
            if d is None: continue
            per.setdefault(arm, {})[q["id"]] = score(d, q["answer"])
    common = set.intersection(*[set(v) for v in per.values()])
    print(f"questions scored in every arm: {len(common)} of {len(QS)}")
    rows = []
    print(f"\n{'arm':34} {'Metaculus baseline':>24} {'mean ln p':>22} {'Brier':>20} {'top-1 acc':>20}")
    for arm, sc in per.items():
        ids = sorted(common)
        cells = []
        for m in ("mb", "ln", "brier", "acc"):
            mu, lo, hi = boot([sc[i][m] for i in ids])
            cells.append(f"{mu:7.3f} [{lo:6.2f},{hi:6.2f}]" if m != "mb" else f"{mu:7.1f} [{lo:6.1f},{hi:6.1f}]")
        print(f"{arm:34} " + " ".join(f"{c:>22}" for c in cells))
    ref = "P (Opus claims)"
    print(f"\nPaired differences (arm - {ref}), bootstrap 95% CI over questions; positive = arm better (Brier sign flipped)")
    for arm, sc in per.items():
        if arm == ref: continue
        ids = sorted(common)
        out = []
        for m, sgn in (("mb", 1), ("brier", -1), ("acc", 1)):
            mu, lo, hi = boot([sgn * (sc[i][m] - per[ref][i][m]) for i in ids])
            out.append(f"{m}: {mu:+6.2f} [{lo:+6.2f},{hi:+6.2f}]")
        print(f"  {arm:34} " + "   ".join(out))
    # per-question table
    show = ["P (Opus claims)", "P' (Opus, leak-corr)", "P-sol", "model A (softmax T=1)", "Jev Choice", "Opus mean of 5", "Sol direct", "Uniform"]
    print("\nPer-question p(true answer) after the EPS floor")
    print(f"{'qid':11} {'K':>2} {'true':>6} " + " ".join(f"{a[:10]:>10}" for a in show) + "  question")
    for q in QS:
        K = len(q["classes"])
        cells = [f"{math.exp(per[a][q['id']]['ln']):10.3f}" if q["id"] in per.get(a, {}) else f"{'-':>10}" for a in show]
        print(f"{q['id']:11} {K:2d} {q['answer']:>6} " + " ".join(cells) + "  " + q["root_question"][:60])
    # leakage diagnostic: correlation of claim-averaged bearing with Jev prior logit, per question pooled
    xs, ys, xs2 = [], [], []
    for q in QS:
        jp, _ = jev_prior(q)
        for model in ("opus", "sol"):
            r = claims_of(q, model)
            if not r: continue
            _, raw = r
            if not raw: continue
            L = {c: logit(cl(jp[c], .02, .98)) for c in q["classes"]}; mL = statistics.mean(L.values())
            for c in q["classes"]:
                xs.append(L[c] - mL); ys.append(statistics.mean(s[c] for _, s in raw))
    if len(xs) > 3:
        mx, my = statistics.mean(xs), statistics.mean(ys)
        rr = sum((a - mx) * (b - my) for a, b in zip(xs, ys)) / math.sqrt(sum((a - mx) ** 2 for a in xs) * sum((b - my) ** 2 for b in ys))
        print(f"\nleak diagnostic: r(centred logit Jev prior_k, mean signed bearing on k over a question's claims) = {rr:.2f} (n={len(xs)} class-cells)")
    # costs
    oc = sum(r.get("cost", 0) for r in LLM.values() if r["model"] == "opus")
    jt = sum((r["response"].get("usage") or {}).get("input_tokens", 0) for r in JEV.values())
    print(f"\ncost: Opus ${oc:.3f} over {sum(r['model']=='opus' for r in LLM.values())} calls; Sol {sum(r['model']=='sol' for r in LLM.values())} calls (no $ reported); Jev {jt} input tokens ~${jt*0.042/1e6:.4f} over {len(JEV)} requests")
    json.dump(per, open(HERE / "scores.json", "w"), indent=1)

if __name__ == "__main__":
    main()
