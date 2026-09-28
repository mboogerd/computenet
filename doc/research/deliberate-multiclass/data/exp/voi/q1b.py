"""Q1 follow-ups: premature breakdown, hybrid policies, cost counts, p-vs-q illustrations, regression cases."""
import random
from tree import *
from q1 import variants_claim, spearman, topk, EPS

def collect(hk, trees, seed):
    rnd = random.Random(seed); hl = Headline(hk); out = []
    for t in range(trees):
        root, nodes = gen_tree(rnd)
        for n in nodes[1:]:
            v, interior = variants_claim(hl, n)
            out.append((t, n, interior, v))
    return out

for hk in ["consensus", "woe", "dfquad"]:
    rows = collect(hk, 300 if hk == "consensus" else 150, 1 if hk == "consensus" else 2)
    print(f"\n## headline={hk}: premature T_p (today) vs E_q, by cause")
    cause = {}
    for t, n, inter, v in rows:
        if v["T_p (today)"] < EPS <= v["E_q"]:
            key = ("interior" if inter else "leaf") + (", p in {0,1}" if n.p in (0.0, 1.0) else ", p interior")
            cause[key] = cause.get(key, 0) + 1
    print(" ", cause)
    tq = {}
    for t, n, inter, v in rows:
        if v["T_q"] < EPS <= v["E_q"]:
            key = "interior" if inter else "leaf"; tq[key] = tq.get(key, 0) + 1
    print("  T_q premature by kind:", tq)
    # hybrid: rank by T_q, confirm stop with E_q when T_q < k*eps
    for k in (1, 2, 3):
        need = sum(1 for *_, v in rows if v["T_q"] < k * EPS)
        prem = sum(1 for *_, v in rows if v["T_q"] < EPS <= v["E_q"])  # the exact confirm makes these 0 when k>=1
        print(f"  hybrid confirm band T_q<{k}eps: exact evaluations needed for {need}/{len(rows)} candidates ({100*need/len(rows):.0f}%); residual premature 0 (was {prem})")
    # exact re-rank of the top-N by T_q: does the top-1 match E_q's top-1?
    by = {}
    for t, n, inter, v in rows: by.setdefault(t, []).append(v)
    for N in (1, 3, 5, 10):
        agree = [];
        for t, vs in by.items():
            if len(vs) < 6: continue
            top = sorted(vs, key=lambda v: -v["T_q"])[:N]
            pick = max(top, key=lambda v: v["E_q"])
            agree.append(pick["E_q"] == max(v["E_q"] for v in vs))
        print(f"  T_q ranks, exact E_q re-ranks top-{N}: top-1 = exact top-1 in {100*sum(agree)/len(agree):.0f}% of trees")

# ---- cost counts (evaluate() calls; one call = one layer's combine over a node's args) ----
print("\n## cost per full refresh, consensus headline (evaluate() calls)")
rnd = random.Random(1); tot_sens = tot_exact_all = tot_exact_c = 0; ntrees = 0; ncand = 0
L_ALL, L_HEAD = len(IDS), len(MEMBERS)
for _ in range(300):
    root, nodes = gen_tree(rnd); ntrees += 1
    # today's sensitivity cells: every node recomputes localPartials: 2 partials x 2 evals x every influence x every layer
    tot_sens += sum(4 * len(n.kids) * L_ALL for n in nodes)
    # exact on demand: 2 resolutions x path length x headline layers, per candidate
    tot_exact_all += sum(2 * n.depth * L_HEAD for n in nodes[1:]); ncand += len(nodes) - 1
print(f"  mean nodes/tree {ncand/ntrees+1:.1f}; today's sensitivity layer full recompute {tot_sens/ntrees:.0f} evals/tree;"
      f" exact E_q for EVERY candidate {tot_exact_all/ntrees:.0f} evals/tree ({tot_exact_all/ncand:.1f} per candidate)")

# ---- p vs q illustrations (consensus) ----
print("\n## p vs q: interior nodes whose credence q differs from Jev's plausibility p (consensus headline)")
hl = Headline("consensus")
def show(name, root, n):
    evaluate(root); v, _ = variants_claim(hl, n)
    print(f"  {name}: p={n.p} q={node_headline(hl, n):.3f}  T_p(today)={v['T_p (today)']:.4f} T_q={v['T_q']:.4f} E_p={v['E_p']:.4f} E_q={v['E_q']:.4f}")
    # martingale check: expected root after resolution under p vs under q, relative to current root
    Rc = hl.of(root.c); R1, R0 = R(hl, n, 1.0), R(hl, n, 0.0); q = node_headline(hl, n)
    print(f"     E[root after] - root now: under p {n.p*R1+(1-n.p)*R0-Rc:+.4f}, under q {q*R1+(1-q)*R0-Rc:+.4f}")
root = Node(0.5); a = Node(1.0, root, 1, 0.75, 1); root.kids = [a]; a.kids = [Node(0.9, a, -1, 1.0, 2)]
show("Jev-certain claim (p=1) under a decisive attack", root, a)
root = Node(0.5); a = Node(0.5, root, 1, 0.75, 1); root.kids = [a]; a.kids = [Node(0.95, a, 1, 1.0, 2)]
show("open claim (p=.5) with a decisive support below", root, a)
root = Node(0.5); a = Node(0.2, root, 1, 0.8, 1); root.kids = [a]; a.kids = [Node(0.95, a, 1, 0.95, 2)]
show("bug's interior case (p=.2, strong support below)", root, a)

# ---- regression cases for the Kotlin test ----
print("\n## regression cases (Kotlin tests)")
for hk in ["woe", "consensus"]:
    hl = Headline(hk)
    root = Node(0.5); top = Node(0.6, root, 1, 1.0, 1); root.kids = [top]; leaf = Node(0.5, top, 1, 0.95, 2); top.kids = [leaf]; evaluate(root)
    v, _ = variants_claim(hl, leaf)
    print(f"  [clamp] root .5 <-sup s=1- top(p=.6) <-sup s=.95- leaf(p=.5), headline {hk}: top per-layer woe={top.c['woe']:.3f};"
          f" today {v['T_p (today)']:.4f} T_q {v['T_q']:.4f} E_q {v['E_q']:.4f}")
    root = Node(0.5); leaf = Node(0.5, root, 1, 0.9, 1); root.kids = [leaf]; evaluate(root)
    v, _ = variants_claim(hl, leaf)
    print(f"  [over-rate] root .5 <-sup s=.9- leaf(p=.5), headline {hk}: today {v['T_p (today)']:.4f} E_q {v['E_q']:.4f}")
    root = Node(0.5); a = Node(1.0, root, 1, 0.75, 1); root.kids = [a]; a.kids = [Node(0.9, a, -1, 1.0, 2)]; evaluate(root)
    v, _ = variants_claim(hl, a)
    print(f"  [p-vs-q] root .5 <-sup s=.75- a(p=1) <-att s=1- b(p=.9), headline {hk}: q_a={node_headline(hl,a):.3f} today {v['T_p (today)']:.4f} E_q {v['E_q']:.4f}")
