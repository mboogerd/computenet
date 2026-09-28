#!/usr/bin/env python3
"""Analysis for e2e2 (computenet-1ow0x). Stdlib only.

Claim -> (energy, kappa): identical to ../e2e/analyze.py, but bearing = ISO "instr" bear (leak/part_b.instr_q), mean of A/B.
  v_ik = score/4; s = 2v-1; strength = (max s - min s)/2; kappa_ik = (s_ik - min)/(max - min); credence = Jev plausibility
  (OUTSIDE_MY_KNOWLEDGE -> .5); energy = strength * credence.
Layers (closed-world log-linear elimination), scale multiplier m applied to both k's:
  woe: w=-ln(1-min(e,.7)), p=2, k=1.2*m     mlp: w=e, p=1, k=1.0*m      P = log-pool(woe, mlp)
  score_k = alpha*log pi_k - m*k_layer*||w(e_i)(1-kappa_ik)||_p ; softmax.
Bases: uniform ("arguments alone"); Opus mean-of-5; Opus sample 0; mean(Opus mean-of-5, Sol).  pi smoothed (1-.01)pi+.01/K.
CV: leave-one-question-out over the union (80 q); the held-out question is scored with the parameter that maximises mean
  ln p (after the scoring floor) on the other 79. Only out-of-sample scores are reported for CV arms.
Scoring floor: p' = (1-EPS)p + EPS/K, EPS=.01 (as e2e).
"""
import json, math, random, statistics, sys
from collections import Counter
from pathlib import Path
HERE = Path(__file__).resolve().parent
E2E = HERE.parent / "e2e"
EPS = 0.01

def load(p):
    out = {}
    if p.exists():
        for l in p.open():
            r = json.loads(l); out[r["key"]] = r
    return out
KQS = json.loads((HERE / "questions_knowledge.json").read_text())["questions"]
FQS = json.loads((E2E / "questions.json").read_text())["questions"]
for q in KQS: q["domain"] = "knowledge"
for q in FQS: q["domain"] = "forecast"
LLM = {**load(E2E / "cache/llm.jsonl"), **load(HERE / "cache/llm.jsonl")}
JEV_OLD, JEV_NEW = load(E2E / "cache/jev.jsonl"), load(HERE / "cache/jev.jsonl")
def ans(rid):
    r = JEV_NEW.get(rid) or JEV_OLD.get(rid)
    return r["response"]["answers"] if r else None

def cl(x, lo, hi): return min(max(x, lo), hi)
def logit(p): return math.log(p / (1 - p))
def softmax(sc):
    m = max(sc.values()); z = {k: math.exp(v - m) for k, v in sc.items()}; t = sum(z.values())
    return {k: v / t for k, v in z.items()}
def pnorm(xs, p):
    xs = [x for x in xs if x > 0]
    return sum(x ** p for x in xs) ** (1 / p) if xs else 0.0
def normalise(d):
    t = sum(d.values()); return {k: v / t for k, v in d.items()}
def smooth(d, e=0.01):
    K = len(d); return {k: (1 - e) * v + e / K for k, v in d.items()}

LAYERS = {"woe": (lambda e: -math.log(1 - min(max(e, 0), 0.7)), 2, 1.2), "mlp": (lambda e: e, 1, 1.0)}

def plaus(a):
    if a["knowledge"]["choice"] == "OUTSIDE_MY_KNOWLEDGE": return 0.5
    return cl(a["plausibility"]["score"] / 4, 0, 1)

CLAIMS, DIAG = {}, {}
def claims_of(q):
    if q["id"] in CLAIMS: return CLAIMS[q["id"]]
    rec = LLM.get(f"opus|propose|{q['id']}|0")
    out, outside, energies = [], 0, []
    if rec:
        for i, _ in enumerate(rec["parsed"][:12]):
            pa = ans(f"PL|opus|{q['id']}|{i}")
            bs = [b for b in (ans(f"BEARI|opus|{q['id']}|{i}|{p}") for p in "AB") if b]
            if not pa or not bs: continue
            v = {c: statistics.mean(b[f"bear|{c}"]["score"] / 4 for b in bs) for c in q["classes"]}
            s = {c: 2 * v[c] - 1 for c in v}
            hi, lo = max(s.values()), min(s.values())
            cred = plaus(pa); outside += pa["knowledge"]["choice"] == "OUTSIDE_MY_KNOWLEDGE"
            e = (hi - lo) / 2 * cred; energies.append(e)
            if hi - lo < 1e-9: continue
            out.append((e, {c: (s[c] - lo) / (hi - lo) for c in s}))
    CLAIMS[q["id"]] = out
    DIAG[q["id"]] = (len(energies), outside, statistics.mean(energies) if energies else float("nan"))
    return out

PEN = {}
def penalties(q):
    if q["id"] not in PEN:
        cls = claims_of(q)
        PEN[q["id"]] = {L: {c: LAYERS[L][2] * pnorm([LAYERS[L][0](e) * (1 - kap[c]) for e, kap in cls], LAYERS[L][1])
                            for c in q["classes"]} for L in LAYERS}
    return PEN[q["id"]]

def P(q, pi, m=1.0, alpha=1.0):
    pen = penalties(q)
    lays = [softmax({c: alpha * math.log(pi[c]) - m * pen[L][c] for c in q["classes"]}) for L in LAYERS]
    return softmax({c: statistics.mean(math.log(max(d[c], 1e-300)) for d in lays) for c in q["classes"]})

def temper(pi, alpha): return softmax({c: alpha * math.log(v) for c, v in pi.items()})

def bases(q):
    K = len(q["classes"]); B = {"uni": {c: 1 / K for c in q["classes"]}}
    ds = [LLM[f"opus|direct|{q['id']}|{s}"]["parsed"] for s in range(5) if f"opus|direct|{q['id']}|{s}" in LLM]
    if ds:
        B["opus1"] = smooth(ds[0]); B["opus5"] = smooth({c: statistics.mean(d[c] for d in ds) for c in q["classes"]})
        B["opus1_raw"] = ds[0]; B["opus5_raw"] = {c: statistics.mean(d[c] for d in ds) for c in q["classes"]}
    sd = LLM.get(f"sol|direct|{q['id']}|0")
    if sd:
        B["sol_raw"] = sd["parsed"]; B["sol"] = smooth(sd["parsed"])
        if ds: B["os"] = smooth({c: (B["opus5_raw"][c] + sd["parsed"][c]) / 2 for c in q["classes"]})
    return B

def score(d, truth):
    K = len(d); p = {c: (1 - EPS) * v + EPS / K for c, v in d.items()}
    lp = math.log(p[truth])
    m = max(d.values()); top = [c for c in d if abs(d[c] - m) < 1e-12]
    return {"ln": lp, "mb": 100 * (lp - math.log(1 / K)) / math.log(K),
            "brier": sum((p[c] - (c == truth)) ** 2 for c in p), "acc": (truth in top) / len(top),
            "conf": m, "hit": float(truth in top) / len(top)}

# ---------------------------------------------------------------- CV
M_GRID = [0, 0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6, 8, 12, 16, 24]
A_GRID = [0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.2, 1.5]

def loo(qs, family, grid):
    """family(q, theta) -> dist. Returns per-question out-of-sample dist and chosen theta per fold."""
    table = {q["id"]: {th: score(family(q, th), q["answer"])["ln"] for th in grid} for q in qs}
    tot = {th: sum(table[q["id"]][th] for q in qs) for th in grid}
    out, chosen = {}, {}
    for q in qs:
        th = max(grid, key=lambda t: (tot[t] - table[q["id"]][t], -abs(hash(str(t))) * 0))
        chosen[q["id"]] = th; out[q["id"]] = family(q, th)
    full = max(grid, key=lambda t: tot[t])
    return out, chosen, full

def kfold_choice(qs, family, grid, k=5, reps=20):
    table = {q["id"]: {th: score(family(q, th), q["answer"])["ln"] for th in grid} for q in qs}
    ch = []
    for r in range(reps):
        ids = [q["id"] for q in qs]; random.Random(r).shuffle(ids)
        for f in range(k):
            train = [i for j, i in enumerate(ids) if j % k != f]
            ch.append(max(grid, key=lambda t: sum(table[i][t] for i in train)))
    return ch

# ---------------------------------------------------------------- metrics
def boot(xs, n=5000, seed=1):
    rnd = random.Random(seed); N = len(xs)
    ms = sorted(sum(xs[rnd.randrange(N)] for _ in range(N)) / N for _ in range(n))
    return sum(xs) / N, ms[int(.025 * n)], ms[int(.975 * n)]

def ece(recs, bins=5):
    B = [[] for _ in range(bins)]
    for r in recs: B[min(int(r["conf"] * bins), bins - 1)].append(r)
    return sum(len(b) * abs(statistics.mean(x["conf"] for x in b) - statistics.mean(x["hit"] for x in b)) for b in B if b) / len(recs)

def boot_ece(recs, n=2000, seed=2):
    rnd = random.Random(seed); N = len(recs)
    v = sorted(ece([recs[rnd.randrange(N)] for _ in range(N)]) for _ in range(n))
    return ece(recs), v[int(.025 * n)], v[int(.975 * n)]

def main():
    ALL = KQS + FQS
    qs = [q for q in ALL if claims_of(q) and "opus5" in bases(q) and "sol" in bases(q)]
    missing = [q["id"] for q in ALL if q not in qs]
    out = []; pr = lambda *a: out.append(" ".join(str(x) for x in a))
    pr(f"questions: {len(qs)} scored ({sum(q['domain']=='knowledge' for q in qs)} knowledge, {sum(q['domain']=='forecast' for q in qs)} forecast); missing {missing}")

    arms, chosen = {}, {}
    def add(name, f): arms[name] = {q["id"]: f(q) for q in qs}
    add("Uniform", lambda q: bases(q)["uni"])
    add("Opus direct (1 sample)", lambda q: bases(q)["opus1_raw"])
    add("Opus mean of 5", lambda q: bases(q)["opus5_raw"])
    add("Sol direct", lambda q: bases(q)["sol_raw"])
    add("Opus5+Sol mean", lambda q: bases(q)["os"])
    def modelA(q):
        pos = {c: ans(f"POS|{q['id']}|{c}") for c in q["classes"]}
        return softmax({c: logit(cl(plaus(pos[c]), .001, .999)) for c in q["classes"]}) if all(pos.values()) else bases(q)["uni"]
    add("model A (softmax T=1)", modelA)
    add("P (args alone, k x1)", lambda q: P(q, bases(q)["uni"]))
    add("P-prior Opus5 (k x1)", lambda q: P(q, bases(q)["opus5"]))
    add("P-prior Opus5+Sol (k x1)", lambda q: P(q, bases(q)["os"]))
    fams = {
        "P (args alone, CV k)": (lambda q, m: P(q, bases(q)["uni"], m), M_GRID),
        "P-prior Opus1 (CV k)": (lambda q, m: P(q, bases(q)["opus1"], m), M_GRID),
        "P-prior Opus5 (CV k)": (lambda q, m: P(q, bases(q)["opus5"], m), M_GRID),
        "P-prior Opus5+Sol (CV k)": (lambda q, m: P(q, bases(q)["os"], m), M_GRID),
        # recalibration controls: does any gain come from arguments, or just from tempering the LLM prior?
        "Opus5 tempered (CV alpha) [control]": (lambda q, a: temper(bases(q)["opus5"], a), A_GRID),
        "Opus5+Sol tempered (CV alpha) [control]": (lambda q, a: temper(bases(q)["os"], a), A_GRID),
        "P-prior Opus5 (CV alpha,k)": (lambda q, t: P(q, bases(q)["opus5"], t[1], t[0]), [(a, m) for a in A_GRID for m in M_GRID]),
    }
    for name, (fam, grid) in fams.items():
        o, ch, full = loo(qs, fam, grid); arms[name] = o; chosen[name] = (ch, full, fam, grid)
    # per-domain CV (train and test within a domain) for the key scale choice
    for name in ("P (args alone, CV k)", "P-prior Opus5 (CV k)"):
        fam, grid = fams[name]
        for dom in ("knowledge", "forecast"):
            sub = [q for q in qs if q["domain"] == dom]
            if not sub: continue
            o, ch, full = loo(sub, fam, grid)
            chosen[f"{name} [within {dom}]"] = (ch, full, fam, grid)
            arms.setdefault(f"{name} [within-domain CV]", {}).update(o)

    per = {a: {q["id"]: score(d[q["id"]], q["answer"]) for q in qs} for a, d in arms.items()}
    json.dump({"per": per, "chosen": {k: (v[0], v[1]) for k, v in chosen.items()}}, open(HERE / "scores2.json", "w"), indent=0, default=str)

    sets = {"KNOWLEDGE": [q for q in qs if q["domain"] == "knowledge"], "FORECAST": [q for q in qs if q["domain"] == "forecast"], "POOLED": qs}
    order = list(arms)
    for sname, sq in sets.items():
        ids = [q["id"] for q in sq]
        if not ids: continue
        pr(f"\n=== {sname} (n={len(ids)}) === mean [95% bootstrap CI]; MB = Metaculus baseline (uniform=0)")
        pr(f"{'arm':42} {'MB':>22} {'ln p':>24} {'Brier':>22} {'top-1':>20} {'ECE(top-1,5 bins)':>22}")
        for a in order:
            sc = per[a]; cells = []
            for m in ("mb", "ln", "brier", "acc"):
                mu, lo, hi = boot([sc[i][m] for i in ids])
                cells.append(f"{mu:6.1f} [{lo:6.1f},{hi:6.1f}]" if m == "mb" else f"{mu:6.3f} [{lo:6.2f},{hi:5.2f}]")
            e, elo, ehi = boot_ece([sc[i] for i in ids])
            cells.append(f"{e:5.3f} [{elo:5.2f},{ehi:4.2f}]")
            pr(f"{a:42} " + " ".join(f"{c:>22}" for c in cells))
        for ref in ("Opus direct (1 sample)", "Opus mean of 5"):
            pr(f"\n  paired (arm - {ref}), 95% bootstrap CI; positive = arm better (Brier sign flipped)")
            for a in order:
                if a == ref: continue
                cells = []
                for m, sg in (("mb", 1), ("ln", 1), ("brier", -1), ("acc", 1)):
                    mu, lo, hi = boot([sg * (per[a][i][m] - per[ref][i][m]) for i in ids])
                    star = "*" if lo > 0 or hi < 0 else " "
                    cells.append(f"{m}:{mu:+7.2f} [{lo:+7.2f},{hi:+7.2f}]{star}" if m == "mb" else f"{m}:{mu:+6.3f} [{lo:+6.3f},{hi:+6.3f}]{star}")
                pr(f"  {a:42} " + "  ".join(cells))

    pr("\n=== CV-chosen parameters (LOO over the pooled set unless noted) ===")
    for name, (ch, full, fam, grid) in chosen.items():
        c = Counter(ch.values())
        pr(f"{name:52} full-data argmax {full}; LOO fold choices {dict(sorted(c.items(), key=lambda x: -x[1]))}")
    for name in ("P (args alone, CV k)", "P-prior Opus5 (CV k)", "P-prior Opus5+Sol (CV k)"):
        fam, grid = fams[name]
        ch = kfold_choice(qs, fam, grid)
        pr(f"{name:52} 5-fold x20 choices {dict(sorted(Counter(ch).items()))}")
    # in-sample profile of mean ln p vs m
    pr("\nIn-sample mean ln p by scale multiplier m (for inspection only, not a result):")
    for name in ("P (args alone, CV k)", "P-prior Opus5 (CV k)"):
        fam, grid = fams[name]
        for dom, sq in sets.items():
            if not sq: continue
            pr(f"  {name:28} {dom:9} " + " ".join(f"m={m}:{statistics.mean(score(fam(q, m), q['answer'])['ln'] for q in sq):.3f}" for m in grid))

    pr("\n=== reliability (top-1 confidence bins, pooled): bin: n / mean conf / accuracy ===")
    for a in ("Opus direct (1 sample)", "Opus mean of 5", "Sol direct", "P (args alone, k x1)", "P (args alone, CV k)", "P-prior Opus5 (CV k)", "Opus5 tempered (CV alpha) [control]", "model A (softmax T=1)"):
        recs = [per[a][q["id"]] for q in qs]; B = [[] for _ in range(5)]
        for r in recs: B[min(int(r["conf"] * 5), 4)].append(r)
        pr(f"  {a:40} " + "  ".join(f"[{i/5:.1f},{(i+1)/5:.1f}): {len(b):2d}/{statistics.mean(x['conf'] for x in b):.2f}/{statistics.mean(x['hit'] for x in b):.2f}" if b else f"[{i/5:.1f},{(i+1)/5:.1f}):  0" for i, b in enumerate(B)))

    pr("\n=== diagnostics ===")
    for dom, sq in sets.items():
        if not sq: continue
        n = sum(DIAG[q["id"]][0] for q in sq); o = sum(DIAG[q["id"]][1] for q in sq)
        me = statistics.mean(e for q in sq for e, _ in CLAIMS[q["id"]]) if any(CLAIMS[q["id"]] for q in sq) else float("nan")
        pr(f"  {dom:9} claims {n}, OUTSIDE_MY_KNOWLEDGE {o} ({o/max(n,1):.0%}), mean claim energy {me:.3f}")
    for kind in sorted({q.get("kind", "forecast") for q in qs}):
        sq = [q for q in qs if q.get("kind", "forecast") == kind]
        pr(f"  kind {kind:18} n={len(sq):2d} " + "  ".join(f"{a[:22]}: acc {statistics.mean(per[a][q['id']]['acc'] for q in sq):.2f} MB {statistics.mean(per[a][q['id']]['mb'] for q in sq):6.1f}" for a in ("Opus mean of 5", "P (args alone, CV k)", "P-prior Opus5 (CV k)")))
    oc = sum(r.get("cost", 0) for k, r in load(HERE / "cache/llm.jsonl").items() if r["model"] == "opus")
    on = sum(r["model"] == "opus" for r in load(HERE / "cache/llm.jsonl").values()); sn = sum(r["model"] == "sol" for r in load(HERE / "cache/llm.jsonl").values())
    jt = sum((r["response"].get("usage") or {}).get("input_tokens", 0) for r in JEV_NEW.values())
    pr(f"\ncost (this run): Opus ${oc:.3f} over {on} calls; Sol {sn} calls; Jev {jt} input tokens ~${jt*0.042/1e6:.4f} over {len(JEV_NEW)} requests")
    txt = "\n".join(out); print(txt); (HERE / "results2.txt").write_text(txt + "\n")

if __name__ == "__main__":
    main()
