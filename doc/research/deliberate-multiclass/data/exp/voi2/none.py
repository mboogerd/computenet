"""None-of-these hurdle: P(k) = P(L) s_k, P(Omega) = 1 - P(L). L is an ordinary binary root (consensus headline);
s is the closed-world LL over the listed tops. Objectives over the joint K+1 outcome distribution."""
import random, math, sys
from multiprocessing import Pool
from lib import *

OM = "Ω"
def joint(pl, s): d = {k: pl * v for k, v in s.items()}; d[OM] = 1 - pl; return d

def eMw(P0, outs):  # outs: [(prob, P)]
    w = weights(P0, 1); return sum(w[k] * sum(p * abs(M(P, k) - M(P0, k)) for p, P in outs) for k in P0)
def eTV(P0, outs): return sum(p * sum(abs(P[k] - P0[k]) for k in P0) for p, P in outs)
def eKL(P0, outs): return sum(p * KL(P, P0) for p, P in outs)

def build(rnd, K=None):
    cat = gen_cat(rnd, 2, 6) if K is None else None
    if K is not None:
        cat = Cat({"A": 1.0}, [])
    Lroot, _ = gen_tree(rnd, maxdepth=rnd.randint(1, 3), maxb=3, cap=15)
    Lroot.p = rnd.uniform(.4, .98); evaluate(Lroot)
    return cat, Lroot

def Lnodes(L):
    out = []
    def go(n): out.append(n); [go(k) for k in n.kids]
    go(L); return out

def rows_for(cat, L):
    s0 = cat.dist(); pl0 = HL.of(L.c); P0 = joint(pl0, s0); rows = []; pre = []
    for n in Lnodes(L):
        q = q_of(n)
        if n is L: a, b = 1.0, 0.0
        else: a, b = R(HL, n, 1.0), R(HL, n, 0.0)
        outs = [(q, joint(a, s0)), (1 - q, joint(b, s0))]
        bin_E = 2 * (q * abs(a - pl0) + (1 - q) * abs(b - pl0))
        rows.append(dict(side="L", eMw_joint=eMw(P0, outs), eTV_joint=eTV(P0, outs), EVSI_log=eKL(P0, outs), binE_L=bin_E,
                         sep=bin_E, PL=pl0)); pre.append(("L", n, outs))
    for n in nodes_of(cat):
        q = q_of(n); s1, sz = ends(cat, n)
        outs = [(q, joint(pl0, s1)), (1 - q, joint(pl0, sz))]
        closed = all_scores(cat, n, s0=s0, s1=s1, sz=sz, q=q)
        rows.append(dict(side="listed", eMw_joint=eMw(P0, outs), eTV_joint=eTV(P0, outs), EVSI_log=eKL(P0, outs),
                         sep=pl0 * closed["eM_w"], sep_tv=pl0 * closed["eTV"], eMw_closed=closed["eM_w"], PL=pl0)); pre.append(("S", n, outs))
    return rows, pre, P0

def sample_L(L, rnd):
    om = {}
    def go(n):
        for k in n.kids: go(k)
        c = HL.of({l: local_vals(n, l, om) for l in MEMBERS}); om[id(n)] = 1.0 if rnd.random() < c else 0.0
    go(L); return om

def one(seed, W=3000):
    rnd = random.Random(seed); cat, L = build(rnd); rows, pre, P0 = rows_for(cat, L)
    lp = [{k: math.log(max(v, 1e-300)) for k, v in outs[0][1].items()} for _, _, outs in pre]
    lz = [{k: math.log(max(v, 1e-300)) for k, v in outs[1][1].items()} for _, _, outs in pre]
    l0 = {k: math.log(max(v, 1e-300)) for k, v in P0.items()}
    acc = [0.0] * len(pre); r2 = random.Random(seed + 99)
    for _ in range(W):
        omS, sf = sample_world(cat, r2); omL = sample_L(L, r2)
        T = {OM: 1.0} if omL[id(L)] == 0.0 else sf
        b = sum(v * l0[k] for k, v in T.items())
        for j, (side, n, _) in enumerate(pre):
            w = (omL if side == "L" else omS)[id(n)]
            lg = lp[j] if w == 1.0 else lz[j]
            acc[j] += sum(v * lg[k] for k, v in T.items()) - b
    for r, a in zip(rows, acc): r["MC_log"] = a / W
    for r in rows: r.setdefault("sep_tv", r["sep"])
    return rows

def reduction_checks():
    rnd = random.Random(4); worst = {"eTV_joint vs binary E_q on L (K=1 listed)": 0, "eMw_joint vs binary (K=1)": 0,
                                     "eTV_joint vs closed eTV (P(L)=1, no L-args)": 0, "eMw_joint vs closed eM_w (P(L)=1)": 0,
                                     "eTV_joint listed-node = P(L)*closed eTV": 0, "eTV_joint L-node = binary E_q on L": 0}
    for _ in range(200):
        cat, L = build(rnd, K=1); rows, _, _ = rows_for(cat, L)
        for r in rows:
            if r["side"] == "L":
                worst["eTV_joint vs binary E_q on L (K=1 listed)"] = max(worst["eTV_joint vs binary E_q on L (K=1 listed)"], abs(r["eTV_joint"] - r["binE_L"]))
                worst["eMw_joint vs binary (K=1)"] = max(worst["eMw_joint vs binary (K=1)"], abs(r["eMw_joint"] - r["binE_L"]))
        cat, L = build(rnd); L.p = 1.0; L.kids = []; evaluate(L)
        # P(L) = consensus of a clamped base .99 -> force exactly 1 by replacing HL for this check
        s0 = cat.dist(); P0 = joint(1.0, s0)
        for n in nodes_of(cat):
            q = q_of(n); s1, sz = ends(cat, n); outs = [(q, joint(1.0, s1)), (1 - q, joint(1.0, sz))]
            c = all_scores(cat, n, s0=s0, s1=s1, sz=sz, q=q)
            worst["eTV_joint vs closed eTV (P(L)=1, no L-args)"] = max(worst["eTV_joint vs closed eTV (P(L)=1, no L-args)"], abs(eTV(P0, outs) - c["eTV"]))
            worst["eMw_joint vs closed eM_w (P(L)=1)"] = max(worst["eMw_joint vs closed eM_w (P(L)=1)"], abs(eMw(P0, outs) - c["eM_w"]))
        cat, L = build(rnd); rows, _, _ = rows_for(cat, L)
        for r in rows:
            if r["side"] == "listed": worst["eTV_joint listed-node = P(L)*closed eTV"] = max(worst["eTV_joint listed-node = P(L)*closed eTV"], abs(r["eTV_joint"] - r["sep_tv"]))
            else: worst["eTV_joint L-node = binary E_q on L"] = max(worst["eTV_joint L-node = binary E_q on L"], abs(r["eTV_joint"] - r["binE_L"]))
    print("## reduction / decomposition checks (max abs error, 200 questions each)")
    for k, v in worst.items(): print(f"- {k}: {v:.1e}")

def worked():
    print("\n## worked: listed = 3-way near tie (A,B,C) with one 'for A' leaf; L has one supporting and one attacking leaf; P(L) varied")
    print("| P(L) base | P(L) | node | eM_w joint | eTV joint | EVSI_log joint | closed eM_w (no Ω) |\n|---|---|---|---|---|---|---|")
    for pb in (.95, .7, .4):
        K = "ABC"; nA = Node(.5); cat = Cat({"A": .34, "B": .33, "C": .33}, [Top(nA, .9, forX(K, "A"))])
        L = Node(pb); sup = Node(.5, L, 1, .8, 1); att = Node(.5, L, -1, .8, 1); L.kids = [sup, att]; evaluate(L)
        rows, pre, P0 = rows_for(cat, L)
        for (side, n, _), r in zip(pre, rows):
            nm = {id(L): "L root", id(sup): "pro-L leaf", id(att): "con-L leaf", id(nA): "for A (listed)"}[id(n)]
            print(f"| {pb} | {r['PL']:.3f} | {nm} | {r['eMw_joint']:.4f} | {r['eTV_joint']:.4f} | {r['EVSI_log']:.4f} | {r.get('eMw_closed', float('nan')):.4f} |")

if __name__ == "__main__":
    reduction_checks(); worked()
    with Pool(14) as p: qs = [t[1:] for t in p.map(one, range(300), chunksize=2)]  # drop the L root itself: it is the hurdle question, not an argument
    print(f"\n## population: 300 hurdle questions (listed K 2-6, P(L) base U(.4,.98)), {sum(map(len, qs))} nodes; MC to completion 3000 worlds (T=Ω when L resolves false)")
    ks = ["eMw_joint", "eTV_joint", "sep", "EVSI_log"]
    print("| objective | per-q Spearman vs MC_log | pooled vs MC_log | per-q vs EVSI_log(joint) | top-pick MC_log capture | share of top picks on L side |\n|---|---|---|---|---|---|")
    for k in ks:
        sp = mean([spearman([r[k] for r in t], [r["MC_log"] for r in t]) for t in qs if len(t) >= 6])
        pe = mean([spearman([r[k] for r in t], [r["EVSI_log"] for r in t]) for t in qs if len(t) >= 6])
        po = spearman([r[k] for t in qs for r in t], [r["MC_log"] for t in qs for r in t])
        cap = []; sideL = 0
        for t in qs:
            m = max(r["MC_log"] for r in t); pick = max(t, key=lambda r: r[k]); sideL += pick["side"] == "L"
            if m > 1e-9: cap.append(pick["MC_log"] / m)
        print(f"| {k} | {sp:.3f} | {po:.3f} | {pe:.3f} | {mean(cap):.3f} | {sideL/len(qs):.2f} |")
    mref = sum(max(t, key=lambda r: r["MC_log"])["side"] == "L" for t in qs) / len(qs)
    print(f"(MC_log's own top pick is on the L side in {mref:.2f} of questions; 'sep' = binary E_q for L-nodes, P(L)*closed eM_w for listed nodes)")
