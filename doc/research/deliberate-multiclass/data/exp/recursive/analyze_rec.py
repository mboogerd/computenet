#!/usr/bin/env python3
"""Offline distributions from recursive deliberate runs (computenet-1ow0x).

Per question the deliberate engine explored each pinned POSITION (= one class) as its own
binary root with recursive argument trees. From each position's DIRECT arguments we read,
per layer L, the propagated source credence c_i^L and edge credence (= strength, lowered by
undercutters/link arguments) s_i^L -- recursion enters only through these two numbers.

(a) model A: the engine's own shares (softmax over consensus credences, T=1), read from /graph.
(b) closed-world log-linear elimination (verify/math/verify.py LL, faithful layer ports):
    mapping per position tree k:  a CON of k  -> claim with kappa=0 at k, 1 elsewhere (penalises k only)
                                  a PRO of k  -> claim with kappa=1 at k, 0 elsewhere (penalises every j != k)
    energy e_i = layer energy (s*c for wlo/woe/mlp; jnb: ln(c LR + (1-c)), LR=((1+s)/(1-s))^.7)
    score_j = alpha log pi_j - k_L * || w_L(e_i) : i penalises j ||_p ; softmax.
    With p=1 (mlp) this equals model A per layer from a neutral base (pro-k rewarding k == penalising
    all others); with p=2 the union pooling is where it differs. Headline LL = log-pool of woe+mlp
    (same as the one-level P arm), pi uniform ("arguments alone"); variants below.
(c) Opus direct from e2e cache (sample 0), collapsed onto our class set by summing.
One-level P recomputed via e2e/analyze.py arms_for, collapsed the same way.
"""
import json, math, sys, os, glob, statistics
from collections import Counter
R = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(R, "../../verify/math")); sys.path.insert(0, os.path.join(R, "../e2e"))
_cwd = os.getcwd(); os.chdir(os.path.join(R, "../../verify/math"))
import verify as V
os.chdir(_cwd)
import analyze as E

PINS = json.load(open(f"{R}/pins.json"))
EQ = {q["id"]: q for q in E.QS}
LAY = ["wlo", "woe", "jnb", "mlp"]
EN = {"wlo": V.energy_prod, "woe": V.energy_prod, "mlp": V.energy_prod, "jnb": V.jnb_energy}
BIN = {"wlo": V.wlo, "woe": V.woe, "jnb": V.jnb, "mlp": V.mlp}

def softmax(sc):
    m = max(sc.values()); z = {c: math.exp(v - m) for c, v in sc.items()}; t = sum(z.values())
    return {c: v / t for c, v in z.items()}
def logpool(ds): return E.logpool(ds)

def load_run(run):
    g = json.load(open(f"{run}/graph.json")); res = json.load(open(f"{run}/result.json"))
    N = {n["ref"]: n for n in g["nodes"]}
    q = next(x for x in g["questions"] if x["root"] == res["root"])
    return g, N, q, res

def analyse(qid, run):
    pin = PINS[qid]; g, N, q, res = load_run(run)
    fr = q["framing"]; assert fr and fr["mode"] == "POSITIONS", "not framed"
    texts = [p["text"] for p in fr["positions"]]
    assert texts == [t for _, t in pin["positions"]], ("position mismatch", texts)
    cls = [c for c, _ in pin["positions"]]
    ref2c = {p["ref"]: c for p, c in zip(fr["positions"], cls)}
    args = {c: [] for c in cls}  # (polarity, {layer: (s, c)})
    for n in g["nodes"]:
        if n["kind"] == "EDGE" and n.get("target") in ref2c:
            src = N[n["source"]]
            args[ref2c[n["target"]]].append((n["polarity"], {L: (n["credences"][L], src["credences"][L]) for L in LAY}))
    # sanity: re-derive each position's per-layer credence from its direct args with the ports
    maxerr = 0.0
    for p, c in zip(fr["positions"], cls):
        node = N[p["ref"]]; b = node.get("plausibility", 0.5) if node.get("plausibility") is not None else 0.5
        for L in LAY:
            att = [EN[L](*a[1][L]) for a in args[c] if a[0] == "ATTACK"]
            sup = [EN[L](*a[1][L]) for a in args[c] if a[0] == "SUPPORT"]
            maxerr = max(maxerr, abs(BIN[L](b, att, sup) - node["credences"][L]))
    base = {c: V.clampBase(N[p["ref"]]["plausibility"] if N[p["ref"]].get("plausibility") is not None else .5) for p, c in zip(fr["positions"], cls)}
    def LLlayer(L, pi):
        claims = []
        for k in cls:
            for pol, v in args[k]:
                e = EN[L](*v[L])
                if e <= 0: continue
                w = V.LAYERS[L][2](e)
                kap = {j: (1.0 if j == k else 0.0) if pol == "SUPPORT" else (0.0 if j == k else 1.0) for j in cls}
                claims.append((w, kap))
        _, _, _, p, kk = V.LAYERS[L]
        return V.LL(claims, pi, p, kk)
    uni = {c: 1 / len(cls) for c in cls}
    piB = E.normalise(base)
    LLs = {L: LLlayer(L, uni) for L in LAY}
    out = {
        "modelA": {c: p["share"] for p, c in zip(fr["positions"], cls)},
        "LL": logpool([LLs["woe"], LLs["mlp"]]),
        "LL_cons": logpool([LLs["wlo"], LLs["woe"], LLs["jnb"]]),
        "LL_fi": logpool([LLlayer("woe", piB), LLlayer("mlp", piB)]),
        **{f"LL_{L}": LLs[L] for L in LAY},
    }
    # one-level + direct, collapsed onto cls
    A = E.arms_for(EQ[qid]); col = pin["collapse"]
    def collapse(d):
        o = {c: 0.0 for c in cls}
        for k, v in d.items(): o[col[k]] += v
        return E.normalise(o)
    out["opus"] = collapse(A["Opus direct"]); out["opus5"] = collapse(A["Opus mean of 5"]); out["P1"] = collapse(A["P (Opus claims)"])
    out["uniform"] = uni
    # tree stats
    cl = [n for n in g["nodes"] if n["kind"] == "CLAIM" and n["root"] == q["root"]]
    posref = set(ref2c)
    def depth_below(n): return n.get("depth")
    st = {"claims": len(cl), "direct_args": sum(len(v) for v in args.values()),
          "maxdepth": max(n.get("depth") or 0 for n in cl), "link_args": sum(1 for n in cl if n.get("onLink")),
          "undercuts": sum(1 for n in cl if n.get("undercuts")), "explored": sum(1 for n in cl if (n.get("rounds") or 0) > 0),
          "depth_hist": dict(sorted(Counter(n.get("depth") for n in cl).items())),
          "status": dict(Counter(n.get("status") for n in cl)),
          "outside_frac": round(sum(1 for n in cl if n.get("plausibility") == 0.5) / len(cl), 2),
          "cost": q["costUsd"], "outcome": res["outcome"], "elapsed_s": round(res["elapsed"]), "stoppedBy": q.get("stoppedBy"),
          "pos_first_impr": [N[r].get("plausibility") for r in ref2c], "port_maxerr": maxerr,
          "pos_cons": [round(N[r]["consensus"], 3) for r in ref2c]}
    return pin["answer"], out, st

if __name__ == "__main__":
    runs = sorted(glob.glob(f"{R}/runs/*/result.json"))
    rows = []; ARMS = ["opus", "opus5", "P1", "modelA", "LL", "LL_cons", "LL_fi", "LL_wlo", "LL_woe", "LL_jnb", "LL_mlp", "uniform"]
    summary = {}
    for rp in runs:
        run = os.path.dirname(rp); name = os.path.basename(run); qid = name.split("-")[0]
        try: truth, out, st = analyse(qid, run)
        except Exception as e: print(name, "SKIP", e); continue
        sc = {a: E.score(out[a], truth) for a in ARMS}
        summary[name] = {"truth": truth, "dists": out, "scores": sc, "stats": st}
        rows.append((name, truth, sc, out, st))
    json.dump(summary, open(f"{R}/summary.json", "w"), indent=1)
    print(f"{'run':22} K true | " + " ".join(f"{a:>7}" for a in ARMS[:7]) + "   (p(true) after EPS floor)")
    for name, truth, sc, out, st in rows:
        print(f"{name:22} {len(out['uniform'])} {truth:5}| " + " ".join(f"{math.exp(sc[a]['ln']):7.3f}" for a in ARMS[:7]))
    main = [r for r in rows if not r[0].endswith("c180")]
    print(f"\nMetaculus baseline mean over {len(main)} runs (max-claims 90), and top-1 acc:")
    for a in ARMS:
        print(f"  {a:8} mb {statistics.mean(r[2][a]['mb'] for r in main):7.1f}   acc {statistics.mean(r[2][a]['acc'] for r in main):.2f}   brier {statistics.mean(r[2][a]['brier'] for r in main):.3f}")
    print("\nTree stats:")
    for name, truth, sc, out, st in rows:
        print(name, json.dumps(st))
    print("\nDistributions:")
    for name, truth, sc, out, st in rows:
        print(name, "true", truth)
        for a in ["opus", "P1", "modelA", "LL", "LL_cons"]:
            print(f"   {a:8}", " ".join(f"{c}:{v:.2f}" for c, v in out[a].items()))
