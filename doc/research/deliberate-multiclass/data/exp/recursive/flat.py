"""Ablation: same direct arguments, but each argument's source credence = its own Jev plausibility and
edge strength = its Jev strength stance (subtrees, undercutters and link arguments ignored).
Difference to the full run = what recursion below depth 1 contributed."""
import json, math, glob, os, statistics
import analyze_rec as A
V, E = A.V, A.E
rows = []
for rp in sorted(glob.glob(f"{A.R}/runs/*/result.json")):
    run = os.path.dirname(rp); name = os.path.basename(run); qid = name.split("-")[0]
    pin = A.PINS[qid]; g, N, q, res = A.load_run(run)
    cls = [c for c, _ in pin["positions"]]; ref2c = {p["ref"]: c for p, c in zip(q["framing"]["positions"], cls)}
    full, flat = {c: [] for c in cls}, {c: [] for c in cls}
    for n in g["nodes"]:
        if n["kind"] == "EDGE" and n.get("target") in ref2c:
            s = N[n["source"]]; k = ref2c[n["target"]]
            pl = s.get("plausibility"); pl = .5 if pl is None else pl
            st = n.get("strength"); st = .5 if st is None else st
            flat[k].append((n["polarity"], st, pl))
            full[k].append((n["polarity"], n["credences"]["woe"], s["credences"]["woe"]))
    def dist(args, L):
        claims = []
        for k in cls:
            for pol, s, c in args[k]:
                e = V.energy_prod(s, c)
                if e <= 0: continue
                claims.append((V.LAYERS[L][2](e), {j: (1.0 if j == k else 0.0) if pol == "SUPPORT" else (0.0 if j == k else 1.0) for j in cls}))
        return V.LL(claims, {c: 1 / len(cls) for c in cls}, V.LAYERS[L][3], V.LAYERS[L][4])
    # mlp/woe with flat uses the Jev stances (identical across layers)
    fl = E.logpool([dist(flat, "woe"), dist(flat, "mlp")])
    t = pin["answer"]
    # how much did recursion move direct-arg credences?
    dc = [abs(N[n["source"]]["consensus"] - (N[n["source"]].get("plausibility") if N[n["source"]].get("plausibility") is not None else .5))
          for n in g["nodes"] if n["kind"] == "EDGE" and n.get("target") in ref2c]
    ds = [abs(n["consensus"] - (n.get("strength") if n.get("strength") is not None else .5))
          for n in g["nodes"] if n["kind"] == "EDGE" and n.get("target") in ref2c]
    summ = json.load(open(f"{A.R}/summary.json"))[name]
    rows.append((name, E.score(fl, t)["mb"], summ["scores"]["LL"]["mb"], statistics.mean(dc), max(dc), statistics.mean(ds), max(ds), fl[t], summ["dists"]["LL"][t]))
print(f"{'run':18} {'mb flat':>8} {'mb full':>8}  mean|dc| max|dc|  mean|ds| max|ds|   p_flat p_full")
for r in rows: print(f"{r[0]:18} {r[1]:8.1f} {r[2]:8.1f}  {r[3]:7.3f} {r[4]:7.3f}  {r[5]:7.3f} {r[6]:7.3f}   {r[7]:.3f}  {r[8]:.3f}")
m = [r for r in rows if not r[0].endswith("c180")]
print("mean mb flat", round(statistics.mean(r[1] for r in m), 1), "full", round(statistics.mean(r[2] for r in m), 1))
