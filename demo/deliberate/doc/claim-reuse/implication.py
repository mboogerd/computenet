"""Experiment b: implication edge vs merge vs constraint, for one-way implication pairs.

For X => Y (Jev says X implies Y but not the converse; gold SAME pairs excluded), coherence
requires c(Y) >= c(X). Starting from each claim's own deliberated consensus in the snapshot:
  merge       Y takes X's credence
  edge        add SUPPORT X -> Y and ATTACK notY -> X, both of strength 1 (notY's per-layer
              credence = 1 - c(Y)), recomputed ONE step with the consensus layers wlo/jnb/woe
              at Semantics.kt defaults (no fixpoint iteration)
  constraint  Y := max(c(Y), c(X)); X unchanged — the smallest coherent move
Layers are re-derived from each node's stance (its plausibility) and its incoming edges'
per-layer strengths and source credences; the script first checks that this reproduces the
snapshot's own layer values.
"""
import common
import layers
from layers import LAYERS, consensus

graphs = {run: layers.Graph(run) for run in common.RUNS}

def layers_of(run, node, extra_S=(), extra_A=()):
    g = graphs[run]
    return g.node_layers(node, lambda ref: g.nodes[ref]["credences"], extra_S, extra_A)

err = [abs(v - n["credences"][k]) for run, g in graphs.items() for n in g.by_text.values()
       if n.get("plausibility") is not None and n["ref"] not in g.roots for k, v in layers_of(run, n).items()]
print(f"recompute check: {len(err)} layer values, max |error| {max(err):.2e}")

claims = common.load_claims()
S = {p["pair"]: p for p in common.expand(common.load("all80.json"), claims)}
G = {g["pair"]: g["label"] for g in common.load("gold.json")}
E = {int(k): v for k, v in common.load("jev_entail_all80.json").items()}
oneway = [p for p, e in E.items() if (e["ab"] == "YES") != (e["ba"] == "YES")]
print(f"Jev one-way implications at cos >= .80: {len(oneway)}; gold labels among them: "
      f"{ {l: sum(G.get(p) == l for p in oneway) for l in ('SAME', 'OVERLAP', None)} }")

rows = []
for p in oneway:
    if G.get(p) == "SAME":
        continue
    a, b = claims[S[p]["a"]], claims[S[p]["b"]]
    X, Y = (a, b) if E[p]["ab"] == "YES" else (b, a)
    nx, ny = graphs[X["run"]].by_text[X["text"]], graphs[Y["run"]].by_text[Y["text"]]
    notY = {k: 1 - v for k, v in ny["credences"].items()}
    rows.append(dict(pair=p, gold=G.get(p), cx=nx["consensus"], cy=ny["consensus"],
                     y_edge=consensus(layers_of(Y["run"], ny, extra_S=[(1.0, nx["credences"])])),
                     x_edge=consensus(layers_of(X["run"], nx, extra_A=[(1.0, notY)])), x=X["text"], y=Y["text"]))
common.save(rows, "implication.json")

n = len(rows)
viol = [r for r in rows if r["cy"] < r["cx"]]
print(f"\npairs analysed (gold SAME excluded): {n}; incoherent as deliberated (c(Y) < c(X)): {len(viol)}, "
      f"mean gap {sum(r['cx'] - r['cy'] for r in viol) / max(1, len(viol)):.3f}")
print("strategy     mean move Y  mean move X  still incoherent  move on already-coherent pairs  verdict flips")
for name, fy, fx in [("merge", lambda r: r["cx"], lambda r: r["cx"]),
                     ("edge", lambda r: r["y_edge"], lambda r: r["x_edge"]),
                     ("constraint", lambda r: max(r["cy"], r["cx"]), lambda r: r["cx"])]:
    coh = [r for r in rows if r["cy"] >= r["cx"]]
    flips = sum((fy(r) - .5) * (r["cy"] - .5) < 0 for r in rows) + sum((fx(r) - .5) * (r["cx"] - .5) < 0 for r in rows)
    print(f"  {name:10} {sum(abs(fy(r) - r['cy']) for r in rows) / n:11.3f}  {sum(abs(fx(r) - r['cx']) for r in rows) / n:11.3f}"
          f"  {sum(fy(r) < fx(r) - 1e-9 for r in rows):16}  "
          f"{sum(abs(fy(r) - r['cy']) + abs(fx(r) - r['cx']) for r in coh) / max(1, len(coh)):30.3f}  {flips:13}")
