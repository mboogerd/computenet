"""Scenarios + sweeps for none.py. Prints markdown to stdout."""
import math, random, copy
from none import *

J0 = Judge(0.0)          # honest judge
J3 = Judge(0.3)          # pilot-style upgrade bias
def row(name, d, keys): return f"| {name} | " + " | ".join(f"{d[k]:.3f}" for k in keys) + " |"

def table(title, sc, cands=CANDS, judges=((J0, "β0"), (J3, "β.3"))):
    keys = list(sc["plaus"]) + [OM]
    print(f"\n### {title}\nprior: plaus={sc['plaus']} bL={sc['bL']} (Ω first impression {1-sc['bL']:.2f})")
    print("| candidate | " + " | ".join(keys) + " |\n|---|" + "---|" * len(keys))
    for c in cands:
        for j, tag in judges:
            if c in ("H-hurdle+D", "H-hurdle+D±", "B1-kappaΩ") or tag == "β0":
                nm = c + (f" [{tag}]" if c in ("H-hurdle+D", "H-hurdle+D±", "B1-kappaΩ") else "")
                print(row(nm, pooled(c, sc, j), keys))

AB = ["A", "B"]; XYZ = ["X", "Y", "Z"]
S = {}
S["S1 routine two-sided, K=2 (A vs B, none possible)"] = dict(plaus={"A": .5, "B": .5}, bL=.95,
    listed=[pro("A", AB)] * 3 + [con("A", AB)] * 3)
S["S1b routine two-sided, K=3 (pros/cons on X)"] = dict(plaus={"X": .4, "Y": .3, "Z": .3}, bL=.95,
    listed=[pro("X", XYZ)] * 3 + [con("X", XYZ)] * 3)
S["S1c contrastive debate, K=3 (X>Y, Y>Z, Z>X)"] = dict(plaus={"X": 1/3, "Y": 1/3, "Z": 1/3}, bL=.95,
    listed=[contrast("X", "Y", XYZ), contrast("Y", "Z", XYZ), contrast("Z", "X", XYZ)] * 2)
S["S2 all implausible, no arguments (Jev coherent: bL=Σp=.3)"] = dict(plaus={"X": .1, "Y": .1, "Z": .1}, bL=.3, listed=[])
S["S2b all implausible, Jev incoherent (bL=.8 though Σp=.3)"] = dict(plaus={"X": .1, "Y": .1, "Z": .1}, bL=.8, listed=[])
S["S3 all eliminated (absolute objection to each, e=.72)"] = dict(plaus={"X": .3, "Y": .3, "Z": .3}, bL=.9,
    listed=[con("X", XYZ, .9, .8), con("Y", XYZ, .9, .8), con("Z", XYZ, .9, .8)])
S["S3b all objected but objections rebutted (c=.2)"] = dict(plaus={"X": .3, "Y": .3, "Z": .3}, bL=.9,
    listed=[con("X", XYZ, .9, .2), con("Y", XYZ, .9, .2), con("Z", XYZ, .9, .2)])
S["S3c all eliminated AND a proposer's explicit none-claim (s.8 c.8)"] = dict(plaus={"X": .3, "Y": .3, "Z": .3}, bL=.9,
    listed=[con("X", XYZ, .9, .8), con("Y", XYZ, .9, .8), con("Z", XYZ, .9, .8)], lclaims=[LC(.8, .8, -1)])
S["S4 one strong answer (2 strong pros X, weak con X)"] = dict(plaus={"X": .4, "Y": .3, "Z": .3}, bL=.9,
    listed=[pro("X", XYZ), pro("X", XYZ), con("X", XYZ, .5, .6)])
BA = ["SeaPeoples", "Drought", "Quakes", "Revolt"]
def posopen(k, s, c): d = pro(k, BA, s, c); d["kind"] = "pos_open"; return d
bronze = [posopen("Drought", .8, .85), posopen("SeaPeoples", .8, .8),
          con("SeaPeoples", BA, .7, .7), con("Quakes", BA, .8, .75), con("Revolt", BA, .7, .7), con("Drought", BA, .7, .6)]
S["S5 Late Bronze Age collapse, listed debate only"] = dict(plaus={"SeaPeoples": .3, "Drought": .3, "Quakes": .1, "Revolt": .1}, bL=.6, listed=bronze)
S["S5b + explicit claim 'systems collapse, no single cause' (s.8 c.75)"] = dict(plaus={"SeaPeoples": .3, "Drought": .3, "Quakes": .1, "Revolt": .1}, bL=.6,
    listed=bronze, lclaims=[LC(.8, .75, -1)])

# judge.absolute / positive / kappa_omega for pos_open
_abs, _pos, _ko = Judge.absolute, Judge.positive, Judge.kappa_omega
def absolute(self, x): return self._n(self.beta) if x["kind"] == "pos_open" else _abs(self, x)
def positive(self, x): return 0.0 if x["kind"] == "pos_open" else _pos(self, x)
def kappa_omega(self, x, pi): return 1.0 if x["kind"] == "pos_open" else _ko(self, x, pi)
Judge.absolute, Judge.positive, Judge.kappa_omega = absolute, positive, kappa_omega

if __name__ == "__main__":
    print("## Scenarios (log-pool over wlo/woe/jnb/mlp; β = simulated Jev upgrade bias)")
    for t, sc in S.items(): table(t, sc)

    # ---------------- R4 class added mid-way (design H), pending kappa policies
    print("\n## R4 class W added mid-deliberation (design H+D, β0)")
    base = S["S3 all eliminated (absolute objection to each, e=.72)"]
    base4 = S["S4 one strong answer (2 strong pros X, weak con X)"]
    print("| start | policy for κ(existing claim, W) until judged | W | Ω | X |\n|---|---|---|---|---|")
    for nm, b in (("S3 all-eliminated", base), ("S4 one strong answer", base4)):
        before = pooled("H-hurdle+D", b, J0)
        print(f"| {nm} | (before W) | — | {before[OM]:.3f} | {before['X']:.3f} |")
        pi = normpi(b["plaus"]); s_now = logpool([cond_shares(L, b["listed"], pi) for L in LAYERS])
        for pol in ("optimistic κ=1", "pessimistic κ=0", "neutral κ=Σ s_k κ_ik", "judged (truth)"):
            lst = []
            for x in b["listed"]:
                y = copy.deepcopy(x)
                if pol.startswith("optimistic"): y["kappa"]["W"] = 1.0
                elif pol.startswith("pessimistic"): y["kappa"]["W"] = 0.0
                elif pol.startswith("neutral"): y["kappa"]["W"] = sum(s_now[k] * x["kappa"][k] for k in s_now)
                else: y["kappa"]["W"] = 0.0 if x["kind"] == "pos" else 1.0  # a pro-X excludes W; an objection to X does not touch W
                lst.append(y)
            sc = dict(plaus=dict(b["plaus"], W=.3), bL=min(.97, b["bL"] + .03), listed=lst)
            d = pooled("H-hurdle+D", sc, J0)
            print(f"| {nm} | {pol} | {d['W']:.3f} | {d[OM]:.3f} | {d['X']:.3f} |")

    # ---------------- R1 K=2 continuity sweep
    print("\n## R1 K=2 continuity sweep (4000 random trees per layer; max |P(A) - today's binary layer|)")
    rnd = random.Random(11); worst = {}
    for _ in range(4000):
        b = rnd.uniform(.01, .99); n1, n2 = rnd.randint(0, 6), rnd.randint(0, 6)
        sup = [(rnd.random(), rnd.random()) for _ in range(n1)]; att = [(rnd.random(), rnd.random()) for _ in range(n2)]
        listed = [C(s, c, {"A": 1., "B": 0.}, "pos") for s, c in sup] + [C(s, c, {"A": 0., "B": 1.}, "neg") for s, c in att]
        for L in LAYERS:
            ref = bin_layer(L, b, [energy(L, s, c) for s, c in sup], [energy(L, s, c) for s, c in att])
            s_ = cond_shares(L, listed, {"A": b, "B": 1 - b})["A"]
            worst[("conditional s_A (all candidates, IIA)", L)] = max(worst.get(("conditional s_A (all candidates, IIA)", L), 0), abs(s_ - ref))
            for cn, bL in (("A0/H, Ω not in play (no L node)", 1.0),):
                worst[(cn, L)] = max(worst.get((cn, L), 0), abs(s_ * bL - ref))
            sc = dict(plaus={"A": b, "B": 1 - b}, bL=.95, listed=listed)
            for cn in ("O-flat", "H-hurdle+D", "A0-hurdle", "B1-kappaΩ"):
                v = cand(cn, L, sc, J0)["A"]
                worst[(cn + " Ω in play, bL=.95", L)] = max(worst.get((cn + " Ω in play, bL=.95", L), 0), abs(v - ref))
                vc = v / (1 - cand(cn, L, sc, J0)[OM])
                worst[(cn + " conditional, Ω in play", L)] = max(worst.get((cn + " conditional, Ω in play", L), 0), abs(vc - ref))
    names = sorted({k[0] for k in worst})
    print("| quantity | " + " | ".join(LAYERS) + " |\n|---|" + "---|" * 4)
    for n in names: print(f"| {n} | " + " | ".join(f"{worst[(n, L)]:.1e}" for L in LAYERS) + " |")

    # ---------------- R2 / R3 random populations
    print("\n## R2/R3 random populations (1500 trees each; ΔΩ = P(Ω) − first-impression Ω)")
    def pop(kind, rnd):
        K = rnd.choice([2, 3, 4]); cls = [f"c{i}" for i in range(K)]
        raw = [rnd.uniform(.1, 1) for _ in cls]; plaus = {c: v / sum(raw) for c, v in zip(cls, raw)}
        bL = rnd.uniform(.8, .97); listed = []
        if kind == "routine":  # pro/con/contrast on classes, but at least one class has NO absolute objection
            safe = rnd.choice(cls)
            for _ in range(rnd.randint(2, 8)):
                r = rnd.random(); k = rnd.choice(cls); s, c = rnd.uniform(.5, 1), rnd.uniform(.5, 1)
                if r < .4: listed.append(pro(k, cls, s, c))
                elif r < .7 and k != safe: listed.append(con(k, cls, s, c))
                else:
                    a, b2 = rnd.sample(cls, 2); listed.append(contrast(a, b2, cls, s, c))
        else:  # every class has at least one standing absolute objection, plus noise
            for k in cls: listed.append(con(k, cls, rnd.uniform(.6, 1), rnd.uniform(.6, 1)))
            for _ in range(rnd.randint(0, 3)):
                a, b2 = rnd.sample(cls, 2); listed.append(contrast(a, b2, cls, rnd.uniform(.3, .9), rnd.uniform(.3, .9)))
        return dict(plaus=plaus, bL=bL, listed=listed)
    print("| candidate | judge | routine mean ΔΩ | routine max ΔΩ | routine P(Ω leads) | all-elim mean P(Ω) | all-elim mean ΔΩ |\n|---|---|---|---|---|---|---|")
    for cn in CANDS:
        for beta in ((0.0, .3, .5) if cn in ("H-hurdle+D", "H-hurdle+D±", "B1-kappaΩ") else (0.0,)):
            j = Judge(beta, sd=.1, seed=5); rnd = random.Random(21); dR = []; lead = 0
            for _ in range(1500):
                sc = pop("routine", rnd); d = pooled(cn, sc, j); dR.append(d[OM] - (1 - sc["bL"]))
                lead += d[OM] >= max(v for k, v in d.items() if k != OM)
            rnd = random.Random(22); pE = []; dE = []
            for _ in range(1500):
                sc = pop("elim", rnd); d = pooled(cn, sc, j); pE.append(d[OM]); dE.append(d[OM] - (1 - sc["bL"]))
            print(f"| {cn} | β{beta} | {sum(dR)/len(dR):+.3f} | {max(dR):+.3f} | {lead/1500:.3f} | {sum(pE)/len(pE):.3f} | {sum(dE)/len(dE):+.3f} |")

    # ---------------- R6 VoI on P(Ω): exact E|ΔP(Ω)| resolving each node's credence to 0/1 w.p. its credence
    print("\n## R6 VoI on none-of-these (exact, no linearisation): E|ΔP(Ω)| when a node resolves true/false")
    sc = S["S5b + explicit claim 'systems collapse, no single cause' (s.8 c.75)"]
    labels = ["drought evidence (pos_open)", "SeaPeoples raids (pos_open)", "con SeaPeoples", "con Quakes", "con Revolt", "con Drought"]
    print("| node | " + " | ".join(["O-flat", "B1-kappaΩ", "A0-hurdle", "H-hurdle+D"]) + " |\n|---|---|---|---|---|")
    def voi(cn, sc, path, idx):
        base = pooled(cn, sc, J0)[OM]; x = sc[path][idx]; p = x["c"]; tot = 0
        for v, w in ((1.0, p), (0.0, 1 - p)):
            s2 = copy.deepcopy(sc); s2[path][idx]["c"] = v; tot += w * abs(pooled(cn, s2, J0)[OM] - base)
        return tot
    for i, lab in enumerate(labels):
        print(f"| {lab} | " + " | ".join(f"{voi(cn, sc, 'listed', i):.3f}" for cn in ["O-flat", "B1-kappaΩ", "A0-hurdle", "H-hurdle+D"]) + " |")
    print("| L-claim 'systems collapse' | " + " | ".join(f"{voi(cn, sc, 'lclaims', 0):.3f}" for cn in ["O-flat", "B1-kappaΩ", "A0-hurdle", "H-hurdle+D"]) + " |")
