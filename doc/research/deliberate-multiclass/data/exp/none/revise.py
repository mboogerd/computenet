"""Revisions after Sol/Opus review: (1) pool P(L) and s_k separately; (2) D rebuilt so each claim's energy is
weighted by ITS OWN judged absoluteness (no decoupled J); (3) Bayesian hurdle HB with per-claim κ_Ω;
(4) judge errors in both directions; (5) reviewers' counterexamples; (6) carve-from-Ω simulated."""
import math, random, copy
from run import *   # scenarios S, builders, patched Judge

class J2(Judge):
    """bu: upgrade comparative->absolute; bd: downgrade absolute->comparative (β<0 in the review)."""
    def __init__(self, bu=0., bd=0., sd=0., seed=0): super().__init__(bu, sd, seed); self.bd = bd
    def absolute(self, x):
        t = 1.0 if x["kind"] == "neg" else 0.0
        return self._n(t * (1 - self.bd) + (1 - t) * self.beta)
    def kappa_omega(self, x, pi):
        if x["kind"] == "pos_open": return 1.0
        if x["kind"] == "pos": t = 0.0
        elif x["kind"] == "neg": t = 1.0 - self.bd
        else: t = sum(pi[c] * x["kappa"].get(c, 1.0) for c in pi)
        return self._n(t + self.beta * (1 - t))

def pool_bin(ps): z = sum(V.logit(min(max(p, 1e-9), 1 - 1e-9)) for p in ps) / len(ps); return V.sig(z)

def PL_layer(name, L, sc, j):
    classes = list(sc["plaus"]); pi = normpi(sc["plaus"]); lst = sc["listed"]; lcl = sc.get("lclaims", [])
    if name == "A0p": return P_L(L, sc["bL"], lcl)
    if name == "Hmin-abs":   # Opus fix: S_abs = min_k noisyOR_i e_i·abs_i·(1-κ_ik), one derived attack
        S = min(nor([x["s"] * x["c"] * j.absolute(x) * (1 - x["kappa"].get(k, 1.0)) for x in lst]) for k in classes) if lst else 0
        return P_L(L, sc["bL"], lcl, (), [(1.0, S)] if S > 0 else [])
    if name == "Hw-abs":     # prior-weighted: S = 1 - Σ π_k Π_i (1 - e_i abs_i (1-κ_ik)) (no junk-class veto)
        S = 1 - sum(pi[k] * math.prod(1 - x["s"] * x["c"] * j.absolute(x) * (1 - x["kappa"].get(k, 1.0)) for x in lst) for k in classes) if lst else 0
        return P_L(L, sc["bL"], lcl, (), [(1.0, S)] if S > 0 else [])
    if name == "HB":         # Bayesian virtual evidence on the hurdle: logit P(L) += log Σπ_kΠL_ik − log ΠL_iΩ
        e = lambda x: min(.99, x["s"] * x["c"])
        Zl = sum(pi[k] * math.prod(1 - e(x) * (1 - x["kappa"].get(k, 1.0)) for x in lst) for k in classes)
        Zo = math.prod(1 - e(x) * (1 - j.kappa_omega(x, pi)) for x in lst)
        return V.sig(V.logit(P_L(L, sc["bL"], lcl)) + math.log(Zl) - math.log(Zo))
    raise KeyError(name)

def dist(name, sc, j):
    """pool P(L) (binary log-odds mean = today's Consensus) and s (log-pool) SEPARATELY."""
    pi = normpi(sc["plaus"])
    s = logpool([cond_shares(L, sc["listed"], pi) for L in LAYERS])
    PL = pool_bin([PL_layer(name, L, sc, j) for L in LAYERS])
    d = {k: PL * v for k, v in s.items()}; d[OM] = 1 - PL; return d

NEW = ["A0p", "Hmin-abs", "Hw-abs", "HB"]
V4 = ["X", "Y", "Z", "V"]
X = dict(S)
X["Sol-R3 extreme: bL=.99, s=c=1 objection to each of 3"] = dict(plaus={"X": .33, "Y": .33, "Z": .33}, bL=.99,
    listed=[con(k, XYZ, 1, 1) for k in XYZ])
X["S3d X,Y,Z objected, junk V p=.02 unobjected"] = dict(plaus={"X": .3, "Y": .3, "Z": .3, "V": .02}, bL=.9,
    listed=[con(k, V4, .9, .8) for k in "XYZ"])
X["Sol-cx: 10 strong contrasts each way + weightless abs objections"] = dict(plaus={"A": .5, "B": .5}, bL=.95,
    listed=[contrast("A", "B", AB, .95, .95)] * 10 + [contrast("B", "A", AB, .95, .95)] * 10 + [con("A", AB, .01, .01), con("B", AB, .01, .01)])
X["Opus-cx: S1 + weak abs objection to B (e=.04)"] = dict(plaus={"A": .5, "B": .5}, bL=.95,
    listed=[pro("A", AB)] * 3 + [con("A", AB)] * 3 + [con("B", AB, .2, .2)])
X["S1' S1 with CONTRASTIVE pros (A fits better than B)"] = dict(plaus={"A": .5, "B": .5}, bL=.95,
    listed=[contrast("A", "B", AB, .9, .9)] * 3 + [con("A", AB)] * 3)
keep = ["S1 routine two-sided, K=2 (A vs B, none possible)", "S1c contrastive debate, K=3 (X>Y, Y>Z, Z>X)",
        "S3 all eliminated (absolute objection to each, e=.72)", "S3b all objected but objections rebutted (c=.2)",
        "S3c all eliminated AND a proposer's explicit none-claim (s.8 c.8)", "S4 one strong answer (2 strong pros X, weak con X)",
        "S5 Late Bronze Age collapse, listed debate only", "S5b + explicit claim 'systems collapse, no single cause' (s.8 c.75)",
        "Sol-R3 extreme: bL=.99, s=c=1 objection to each of 3", "S3d X,Y,Z objected, junk V p=.02 unobjected",
        "Sol-cx: 10 strong contrasts each way + weightless abs objections", "Opus-cx: S1 + weak abs objection to B (e=.04)",
        "S1' S1 with CONTRASTIVE pros (A fits better than B)"]
if __name__ == "__main__":
    js = [(J2(), "0"), (J2(bu=.3), "up.3"), (J2(bd=.3), "down.3")]
    print("## Revised: P(Ω) per scenario [first-impression Ω]; judges 0 / up.3 / down.3")
    print("| scenario | Ω0 | " + " | ".join(NEW) + " |\n|---|---|" + "---|" * len(NEW))
    for t in keep:
        sc = X[t]
        cells = []
        for n in NEW:
            vs = [dist(n, sc, j)[OM] for j, _ in js]
            cells.append(f"{vs[0]:.3f}" if n == "A0p" else "/".join(f"{v:.3f}" for v in vs))
        print(f"| {t} | {1-sc['bL']:.2f} | " + " | ".join(cells) + " |")

    # populations: routine vs all-elim (+ a 'contaminated routine': routine + weak abs objections to every class)
    def pop(kind, rnd):
        K = rnd.choice([2, 3, 4]); cls = [f"c{i}" for i in range(K)]
        raw = [rnd.uniform(.1, 1) for _ in cls]; plaus = {c: v / sum(raw) for c, v in zip(cls, raw)}
        bL = rnd.uniform(.8, .97); lst = []
        if kind in ("routine", "contam"):
            safe = rnd.choice(cls)
            for _ in range(rnd.randint(2, 8)):
                r = rnd.random(); k = rnd.choice(cls); s, c = rnd.uniform(.5, 1), rnd.uniform(.5, 1)
                if r < .4: lst.append(pro(k, cls, s, c))
                elif r < .7 and k != safe: lst.append(con(k, cls, s, c))
                else: a, b2 = rnd.sample(cls, 2); lst.append(contrast(a, b2, cls, s, c))
            if kind == "contam": lst += [con(k, cls, .2, .2) for k in cls]
        else:
            for k in cls: lst.append(con(k, cls, rnd.uniform(.6, 1), rnd.uniform(.6, 1)))
            for _ in range(rnd.randint(0, 3)): a, b2 = rnd.sample(cls, 2); lst.append(contrast(a, b2, cls, rnd.uniform(.3, .9), rnd.uniform(.3, .9)))
        return dict(plaus=plaus, bL=bL, listed=lst)
    print("\n## Populations (1500 each; separate pooling; judge sd .1). ΔΩ = P(Ω) − (1−bL)")
    print("| candidate | judge | routine mean/max ΔΩ | routine+weak objections to every class mean/max ΔΩ | all-elim mean ΔΩ / mean P(Ω) |\n|---|---|---|---|---|")
    for n in NEW:
        for bu, bd in ((0, 0), (.3, 0), (0, .3)) if n != "A0p" else ((0, 0),):
            j = J2(bu, bd, sd=.1, seed=5); out = []
            for kind, seed in (("routine", 21), ("contam", 23), ("elim", 22)):
                rnd = random.Random(seed); ds = []; ps = []
                for _ in range(1500):
                    sc = pop(kind, rnd); d = dist(n, sc, j); ds.append(d[OM] - (1 - sc["bL"])); ps.append(d[OM])
                out.append((sum(ds) / len(ds), max(ds), sum(ps) / len(ps)))
            print(f"| {n} | up{bu}/down{bd} | {out[0][0]:+.3f}/{out[0][1]:+.3f} | {out[1][0]:+.3f}/{out[1][1]:+.3f} | {out[2][0]:+.3f} / {out[2][2]:.3f} |")

    # carve-from-Ω simulated, then claims re-judged one at a time against W (HB and Hw-abs)
    print("\n## R4 carve-from-Ω then sequential re-judgment of existing claims against W (q=.5)")
    for t in ("S3 all eliminated (absolute objection to each, e=.72)", "S4 one strong answer (2 strong pros X, weak con X)"):
        b = X[t]
        for n in ("Hw-abs", "HB"):
            before = dist(n, b, J2()); PO = before[OM]; q = .5
            path = [f"carve W={PO*q:.3f} Ω={PO*(1-q):.3f}"]
            # full model after adding W: bL' coherent with carve: bL' = bL + (1-bL)q ; pending claims -> W treated like Ω (κ_W = judged κ_Ω)
            for m in range(len(b["listed"]) + 1):
                lst = []
                for i, x in enumerate(b["listed"]):
                    y = copy.deepcopy(x)
                    if i < m: y["kappa"]["W"] = 0.0 if x["kind"] == "pos" else 1.0
                    else: y["kappa"]["W"] = J2().kappa_omega(x, normpi(b["plaus"]))  # pending: W inherits Ω's judged column
                    lst.append(y)
                sc = dict(plaus=dict(b["plaus"], W=b["plaus"]["X"] * 0 + (1 - b["bL"]) * q * sum(b["plaus"].values()) / b["bL"]),
                          bL=b["bL"] + (1 - b["bL"]) * q, listed=lst)
                d = dist(n, sc, J2()); path.append(f"{m} judged: W={d['W']:.3f} Ω={d[OM]:.3f}")
            print(f"- {t[:24]} / {n}: " + "; ".join(path))
