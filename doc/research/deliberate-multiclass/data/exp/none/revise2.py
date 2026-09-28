"""Opus obj.4 (single-edge R3 cap): D as a log-odds shift equal to the layer's own penalty of the
least-objected class, from absoluteness-weighted claims (accumulates like the layers do)."""
import math, random
import revise as R
from revise import *
def shift_min(L, sc, j, soft):
    classes = list(sc["plaus"]); pi = normpi(sc["plaus"])
    pen = penalties(L, [dict(x, s=x["s"] * j.absolute(x)) for x in sc["listed"]], classes)  # abs scales strength
    if soft: return -math.log(sum(pi[k] * math.exp(-pen[k]) for k in classes))
    return min(pen.values())
_old = R.PL_layer
def PL2(name, L, sc, j):
    if name in ("Hmin-pen", "Hlse-pen"):
        return V.sig(V.logit(P_L(L, sc["bL"], sc.get("lclaims", []))) - shift_min(L, sc, j, name == "Hlse-pen"))
    return _old(name, L, sc, j)
R.PL_layer = PL2
NEW2 = ["Hmin-abs", "Hmin-pen", "Hlse-pen"]
X["10 decisive objections per class (K=3, bL=.9)"] = dict(plaus={"X": .3, "Y": .3, "Z": .3}, bL=.9,
    listed=[con(k, XYZ, .95, .95) for k in XYZ for _ in range(10)])
js = [(J2(), "0"), (J2(bu=.3), "up.3"), (J2(bd=.3), "down.3")]
print("| scenario | Ω0 | " + " | ".join(NEW2) + " |\n|---|---|" + "---|" * len(NEW2))
for t in keep + ["10 decisive objections per class (K=3, bL=.9)"]:
    sc = X[t]; print(f"| {t} | {1-sc['bL']:.2f} | " + " | ".join("/".join(f"{dist(n, sc, j)[OM]:.3f}" for j, _ in js) for n in NEW2) + " |")
def pop(kind, rnd):
    K = rnd.choice([2, 3, 4]); cls = [f"c{i}" for i in range(K)]
    raw = [rnd.uniform(.1, 1) for _ in cls]; plaus = {c: v / sum(raw) for c, v in zip(cls, raw)}
    bL = rnd.uniform(.8, .97); lst = []
    if kind != "elim":
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
print("\n| candidate | judge | routine mean/max ΔΩ | +weak objections to every class | all-elim mean ΔΩ / P(Ω) |\n|---|---|---|---|---|")
for n in NEW2:
    for bu, bd in ((0, 0), (.3, 0), (0, .3)):
        j = J2(bu, bd, sd=.1, seed=5); out = []
        for kind, seed in (("routine", 21), ("contam", 23), ("elim", 22)):
            rnd = random.Random(seed); ds = []; ps = []
            for _ in range(1500):
                sc = pop(kind, rnd); d = dist(n, sc, j); ds.append(d[OM] - (1 - sc["bL"])); ps.append(d[OM])
            out.append((sum(ds) / len(ds), max(ds), sum(ps) / len(ps)))
        print(f"| {n} | up{bu}/down{bd} | {out[0][0]:+.3f}/{out[0][1]:+.3f} | {out[1][0]:+.3f}/{out[1][1]:+.3f} | {out[2][0]:+.3f} / {out[2][2]:.3f} |")
