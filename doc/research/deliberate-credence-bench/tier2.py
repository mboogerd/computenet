#!/usr/bin/env python3
"""Tier 2 of the deliberate credence benchmark: real-knowledge argument trees with a known truth value at EVERY claim,
judged by Jev with the engine's own prompts (JevJudge.kt: CRED-01 plausibility + knowledge, CRED-02 relation strength).

  uv run --with numpy --with scipy python tier2.py build     # trees.json from local Wikidata (CC0), seeded
  uv run --with numpy --with scipy python tier2.py jev       # Jev judgements, cached in cache/jev.jsonl (TYPESAFE_API_KEY)
  uv run --with numpy --with scipy python tier2.py analyze   # offline: rules vs truth -> tier2_results.txt

Trees (two templates, from ../deliberate-multiclass/data/exp/e2e2/wd_*.json):
  born:  root "A was born before B."  children: "A was born before T." (+), "B was born in T or later." (+),
         "A was born after T2." (-), a distractor "A was a painter/composer." (+, true, logically irrelevant);
         grandchildren under each threshold child: "A was born in Y." with Y the true year or a wrong one, side by
         whether Y is on the child's side of its threshold (decisive if true).
  north: the same shape for "City A lies further north than city B." with latitude thresholds and stated latitudes.
Thresholds and stated values are drawn so that children and grandchildren are true or false in roughly equal measure:
refuted supports and true attacks are the point. Truth of every claim comes from Wikidata.
Scored against truth at every argued claim (log loss, Brier, accuracy) - there is no exact posterior here."""
import json, math, os, sys, threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import numpy as np

HERE = Path(__file__).resolve().parent
WD = HERE.parent / "deliberate-multiclass/data/exp/e2e2"
CACHE = HERE / "cache"; CACHE.mkdir(exist_ok=True)
TREES = HERE / "trees.json"

# ---------------------------------------------------------------- build
def build(seed=20261003, n_born=120, n_north=80):
    rng = np.random.default_rng(seed)
    people = []
    for f, prof in (("wd_painters.json", "painter"), ("wd_composers.json", "composer")):
        for r in json.load(open(WD / f)):
            try: people.append((r["pLabel"], int(r["dob"][:4]), prof))
            except (KeyError, ValueError): pass
    cities = []
    for r in json.load(open(WD / "wd_cities.json")):
        lon, lat = map(float, r["coord"][6:-1].split())
        if lat > 6: cities.append((r["cLabel"], lat, r["countryLabel"]))
    trees = []

    def tree(kind, A, B, va, vb, fmt_root, fmt_before, fmt_after, fmt_val, distractor, round_to, gap):
        """va/vb: the compared quantity (birth year, or latitude negated so 'before' = 'further north' reads as '<')."""
        nodes = []
        def add(parent, text, sign, truth): nodes.append(dict(parent=parent, text=text, sign=sign, truth=bool(truth))); return len(nodes) - 1
        root = add(-1, fmt_root(A, B), 0, va < vb)
        lo_, hi_ = sorted((va, vb))
        def thr():  # a threshold: half the time between the two values, otherwise just outside on a random side
            if rng.random() < .5: return round_to(rng.uniform(lo_, hi_))
            return round_to(rng.choice([lo_ - rng.uniform(1, gap), hi_ + rng.uniform(1, gap)]))
        def stated(v, T):  # the true value, or a wrong one that may cross the threshold
            if rng.random() < .55: return v, True
            w = round_to(v + rng.choice([-1, 1]) * rng.uniform(.4 * gap, 2 * gap)); return w, w == v
        T1 = thr(); c1 = add(root, fmt_before(A, T1), +1, va < T1)
        T2 = thr(); c2 = add(root, fmt_after(B, T2), +1, vb >= T2)
        T3 = thr(); c3 = add(root, fmt_after(A, T3), -1, va >= T3)
        add(root, distractor(A), +1, True)
        for c, who, v, T, below_supports in ((c1, A, va, T1, True), (c2, B, vb, T2, False), (c3, A, va, T3, False)):
            seen = set()
            for _ in range(rng.integers(1, 3)):
                w, true = stated(v, T)
                if w in seen: continue
                seen.add(w)
                sign = +1 if (w < T) == below_supports else -1
                add(c, fmt_val(who, w), sign, true)
        trees.append(dict(kind=kind, root_question=fmt_root(A, B)[:-1] + "?", nodes=nodes))

    pairs = set()
    while len([t for t in trees if t["kind"] == "born"]) < n_born:
        a, b = rng.choice(len(people), 2, replace=False); A, B = people[a], people[b]
        if (a, b) in pairs or not 3 <= abs(A[1] - B[1]) <= 60: continue
        pairs.add((a, b))
        tree("born", A[0], B[0], A[1], B[1],
             lambda a_, b_: f"{a_} was born before {b_}.", lambda w, T: f"{w} was born before {T}.",
             lambda w, T: f"{w} was born in {T} or later.", lambda w, y: f"{w} was born in {y}.",
             lambda w, p=A[2]: f"{w} was a {p}.", lambda x: int(round(x)), 15)
    while len(trees) < n_born + n_north:
        a, b = rng.choice(len(cities), 2, replace=False); A, B = cities[a], cities[b]
        if (a, b) in pairs or not 1.5 <= abs(A[1] - B[1]) <= 25: continue
        pairs.add((a, b))
        # compare on negated latitude so that "smaller" = further north, reusing the same tree shape
        tree("north", A[0], B[0], -A[1], -B[1],
             lambda a_, b_: f"{a_} lies further north than {b_}.", lambda w, T: f"{w} lies north of latitude {abs(T):.0f}°N.",
             lambda w, T: f"{w} lies at or south of latitude {abs(T):.0f}°N.", lambda w, v: f"{w} lies at latitude {abs(v):.0f}°N.",
             lambda w, c=A[2]: f"{w} is a city in {c}.", lambda x: float(min(round(x), -1)), 4)
    TREES.write_text(json.dumps(trees, indent=0))
    n = sum(len(t["nodes"]) for t in trees); tr = sum(x["truth"] for t in trees for x in t["nodes"])
    print(f"{len(trees)} trees, {n} claims ({tr / n:.0%} true); roots true {np.mean([t['nodes'][0]['truth'] for t in trees]):.0%}")

# ---------------------------------------------------------------- Jev (the engine's prompts, verbatim)
sys.path.insert(0, str(WD.parent / "e2e")); sys.path.insert(0, str(WD.parent.parent / "pilot"))
STRENGTH_LEVELS = [
    "Irrelevant: assuming the child claim is true, it would not change the likelihood of the parent claim in the stated direction, or would bear only in the opposite direction.",
    "Weak: assuming the child claim is true, it would move the parent claim only slightly in the stated direction because it is peripheral or readily outweighed.",
    "Moderate: assuming the child claim is true, it would make a meaningful difference to the parent claim, but several ordinary considerations could still outweigh it.",
    "Strong: assuming the child claim is true, it would substantially move the parent claim as one of the main considerations, though it would not settle the parent claim by itself.",
    "Decisive: assuming the child claim is true, the parent claim would be settled in the stated direction except under exceptional conditions.",
]
def strength_q(verb):
    return {"strength": {"type": "score", "criteria": STRENGTH_LEVELS, "instructions":
        f"`child_claim` is offered as an argument that {verb} `parent_claim`. Assume `child_claim` is true. How strongly would it "
        "then bear on `parent_claim` in that direction (`direction`)? Rate only the strength of the connection, not whether "
        "`child_claim` is actually true. If it would bear in the opposite direction, or not at all, rate it irrelevant."}}

def requests():
    import run as R
    out = []
    for ti, t in enumerate(json.load(open(TREES))):
        for ni, n in enumerate(t["nodes"]):
            out.append((f"PL|{ti}|{ni}", {"root_question": t["root_question"], "claim": n["text"]}, R.plaus_questions()))
            if n["parent"] >= 0:
                verb = "supports" if n["sign"] > 0 else "attacks"
                out.append((f"ST|{ti}|{ni}", {"root_question": t["root_question"], "parent_claim": t["nodes"][n["parent"]]["text"],
                                              "child_claim": n["text"], "direction": verb}, strength_q(verb)))
    return out

def load_cache():
    p = CACHE / "jev.jsonl"
    return {(r := json.loads(l))["key"]: r for l in p.open()} if p.exists() else {}

def jev(max_usd=2.0, conc=8):
    import run as R
    key = os.environ.get("TYPESAFE_API_KEY") or sys.exit("TYPESAFE_API_KEY not set")
    done = load_cache(); todo = [r for r in requests() if r[0] not in done]
    toks = [sum((r["response"].get("usage") or {}).get("input_tokens", 0) for r in done.values())]; lock = threading.Lock()
    print(f"{len(todo)} Jev requests to make; spent so far ~${toks[0] * R.USD_PER_M / 1e6:.4f}", flush=True)
    def one(r):
        rid, state, qs = r
        if toks[0] * R.USD_PER_M / 1e6 > max_usd: return "SKIPPED (cap)"
        try: resp = R.post({"model": "jev-latest", "state": state, "questions": qs}, key)
        except Exception as e: return f"FAILED {e}"
        with lock:
            toks[0] += (resp.get("usage") or {}).get("input_tokens", 0)
            with (CACHE / "jev.jsonl").open("a") as f: f.write(json.dumps({"key": rid, "state": state, "response": resp}) + "\n")
        return "ok"
    with ThreadPoolExecutor(conc) as ex: res = list(ex.map(one, todo))
    print(f"done: {res.count('ok')} ok, {len(res) - res.count('ok')} not; Jev ~${toks[0] * R.USD_PER_M / 1e6:.4f}")

# ---------------------------------------------------------------- analyze
def forest():
    """tier1-shaped arrays (children after parents; depth from root) with Jev's base and strength."""
    J = load_cache(); trees = json.load(open(TREES))
    P, D, SG, S, BASE, T, TREE, KIND, OUT, NI = [], [], [], [], [], [], [], [], [], []
    for ti, t in enumerate(trees):
        off = len(P)
        for ni, n in enumerate(t["nodes"]):
            pl = J[f"PL|{ti}|{ni}"]["response"]["answers"]
            out = pl["knowledge"]["choice"] == "OUTSIDE_MY_KNOWLEDGE"
            BASE.append(.5 if out else min(max(pl["plausibility"]["score"] / 4, 0), 1)); OUT.append(out)
            P.append(-1 if n["parent"] < 0 else off + n["parent"]); D.append(0 if n["parent"] < 0 else D[off + n["parent"]] + 1)
            SG.append(float(n["sign"]) if n["parent"] >= 0 else 1.0)
            S.append(min(max(J[f"ST|{ti}|{ni}"]["response"]["answers"]["strength"]["score"] / 4, 0), 1) if n["parent"] >= 0 else 0.0)
            T.append(n["truth"]); TREE.append(ti); KIND.append(t["kind"]); NI.append(ni)
    P, D = np.array(P), np.array(D)
    nk = np.bincount(P[P >= 0], minlength=len(P))
    sub = np.zeros(len(P), int)
    for i in range(len(P) - 1, -1, -1):
        if P[i] >= 0: sub[P[i]] = max(sub[P[i]], sub[i] + 1)
    return dict(parent=P, depth=D, sign=np.array(SG), s=np.array(S), base=np.clip(np.array(BASE), .01, .99),
                truth=np.array(T), tree=np.array(TREE), kind=np.array(KIND), outside=np.array(OUT), nk=nk, sub=sub, ni=np.array(NI))

def analyze():
    import tier1 as T1
    from scipy.optimize import minimize
    F = forest(); y = F["truth"].astype(float); argued = F["nk"] > 0
    out = []
    def pr(*a): s = " ".join(str(x) for x in a); print(s, flush=True); out.append(s)

    pr(f"{F['tree'].max() + 1} trees, {len(y)} claims, {int(argued.sum())} argued (roots + threshold claims); Jev outside-knowledge on {F['outside'].mean():.0%}")
    # --- the judge's error, measured
    leaf = F["nk"] == 0; ll = lambda p, t: float(T1.ce(p, t).mean())
    pr("\nJEV AS JUDGE")
    for name, m in (("leaf claims (stated values, distractors)", leaf), ("argued claims, judged in isolation", argued)):
        b = F["base"][m]; t = y[m]
        pr(f"  plausibility on {name:42} n={m.sum():4d}  log loss {ll(b, t):.3f} (uninformed .693)  accuracy {np.mean((b > .5) == t):.2f}  "
           f"mean on true {b[t == 1].mean():.2f} / on false {b[t == 0].mean():.2f}")
    ch = F["parent"] >= 0; grand = ch & (F["depth"] == 2); kid = ch & (F["depth"] == 1)
    dis = kid & (F["ni"] == 4)
    for name, m in (("stated value -> threshold claim (decisive if true)", grand), ("threshold claim -> root (needs its partner)", kid & ~dis),
                    ("distractor -> root (logically irrelevant)", dis)):
        pr(f"  strength, {name:50} mean {F['s'][m].mean():.2f}  share rated irrelevant {np.mean(F['s'][m] < .125):.2f}  decisive {np.mean(F['s'][m] > .875):.2f}")

    # --- rules
    def rules(G, th):
        R = {"base only (Jev, no arguments)": G["base"]}
        for k in T1.LAYERS: R[k] = T1.propagate(G, *T1.LAYERS[k])
        R["consensus(wlo,jnb,woe)"] = T1.consensus(G)
        R["bp-sym (parameter-free)"] = T1.propagate(G, T1.bp_sym_term, T1.logodds_combine)
        for k, t in th.items(): R[k] = T1.propagate(G, *T1.fam(t))
        return R
    tier1 = json.load(open(HERE / "tier1_results.json"))["fitted"]
    th = {"family, tuned on tier1 exact": tier1["tuned family"], "family, tuned on tier1 noisy": tier1["tuned on noisy inputs"]}
    # 2-fold by tree: fit the family against TRUTH on one half, score the other; and swap
    trees = np.unique(F["tree"]); rng = np.random.default_rng(0); half = set(rng.permutation(trees)[:len(trees) // 2])
    inA = np.array([t in half for t in F["tree"]])
    def fit_truth(mask, fixed=None):
        fixed = fixed or {}; free = [k for k in T1.FAM if k not in fixed]
        def loss(v):
            th_ = dict(T1.FAM0); th_.update(fixed); th_.update(zip(free, v)); return T1.ce(T1.propagate(F, *T1.fam(th_))[mask & argued], y[mask & argued]).mean() + 1e-3 * np.sum((np.array(v) - [T1.FAM0[k] for k in free]) ** 2)
        r = minimize(loss, [T1.FAM0[k] for k in free], method="Nelder-Mead", options=dict(maxiter=3000))
        th_ = dict(T1.FAM0); th_.update(fixed); th_.update(zip(free, r.x)); return th_
    VARIANTS = {"family, tuned on tier2 (2-fold CV)": {}, "  ... doubted argument inert (eta=0)": {"eta": 0.0},
                "  ... inert, one weight for support/attack": {"eta": 0.0, "w_att": None}}
    CV = {}
    for name, fx in VARIANTS.items():
        tie = "w_att" in fx; fx = {k: v for k, v in fx.items() if v is not None}
        if tie:   # tie attack to support: fit with w_att pinned to the current w_sup, by re-parameterising through FAM0
            def fit_tied(mask):
                def loss(v):
                    th_ = dict(T1.FAM0); th_.update(fx); th_.update(zip(["alpha", "w_sup", "log_gamma", "log_k", "beta"], v)); th_["w_att"] = th_["w_sup"]
                    return T1.ce(T1.propagate(F, *T1.fam(th_))[mask & argued], y[mask & argued]).mean()
                r = minimize(loss, [1, 1, 0, 0, 0], method="Nelder-Mead", options=dict(maxiter=3000))
                th_ = dict(T1.FAM0); th_.update(fx); th_.update(zip(["alpha", "w_sup", "log_gamma", "log_k", "beta"], r.x)); th_["w_att"] = th_["w_sup"]; return th_
            CV[name] = (fit_tied(inA), fit_tied(~inA))
        else:
            CV[name] = (fit_truth(inA, fx), fit_truth(~inA, fx))
    thA, thB = CV["family, tuned on tier2 (2-fold CV)"]

    def report(title, G):
        R = rules(G, th)
        for name, (a_, b_) in CV.items(): R[name] = np.where(inA, T1.propagate(G, *T1.fam(b_)), T1.propagate(G, *T1.fam(a_)))
        sl = {"all argued": argued, "roots": argued & (F["depth"] == 0), "threshold claims": argued & (F["depth"] == 1),
              "born": argued & (F["kind"] == "born"), "north": argued & (F["kind"] == "north")}
        pr(f"\n{title}\n  log loss vs truth (lower is better; Δ vs base only in brackets), accuracy on all argued")
        pr(f"  {'rule':38}" + "".join(f"{k:>22}" for k in sl) + "   acc")
        for name, p in R.items():
            row = ""
            for m in sl.values():
                a, b = ll(p[m], y[m]), ll(G["base"][m], y[m]); row += f"{a:12.3f} [{a - b:+.3f}]"
            pr(f"  {name:38}{row}  {np.mean((p[argued] > .5) == y[argued]):.2f}")
        return R
    report("JEV JUDGEMENTS (base and strength from Jev)", F)
    G = dict(F); G["base"] = np.where(F["nk"] == 0, np.where(y > 0, .97, .03), F["base"])
    report("ORACLE LEAVES (leaf claims set to their truth, .97/.03; strengths still Jev) - the rule + strength part alone", G)
    for name, (a_, b_) in CV.items():
        pr(f"\n{name.strip()} fitted on the two halves: " + " | ".join(", ".join(f"{k}={(math.exp(v) if k.startswith('log_') else v):.2f}".replace('log_', '') for k, v in t.items()) for t in (a_, b_)))
    json.dump({k: v for k, v in CV.items()}, open(HERE / "tier2_fitted.json", "w"), indent=1)
    (HERE / "tier2_results.txt").write_text("\n".join(out) + "\n")

if __name__ == "__main__":
    {"build": build, "jev": jev, "analyze": analyze}[sys.argv[1]]()
