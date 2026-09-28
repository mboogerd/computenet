"""Independent checks of combine.py / voi_categorical.py claims (computenet-1ow0x). Stdlib only.
Kotlin layers ported from Semantics.kt / agora DfQuad directly (not from combine.py)."""
import math, random, sys
sys.path.insert(0, "../../combine")
import combine as C

# ---------------- faithful Kotlin ports ----------------
FLOOR = 0.01
def clampBase(b): return min(max(b, FLOOR), 1 - FLOOR)
def logit(p): return math.log(p / (1 - p))
def sig(z): return 1 / (1 + math.exp(-z))
def pnorm(xs, p):
    if not xs: return 0.0
    if p >= 50: return max(xs)
    return sum(x ** p for x in xs) ** (1 / p)
def cl01(x): return min(max(x, 0.0), 1.0)
def energy_prod(s, c): return cl01(s) * cl01(c)

def wlo(b, att, sup, alpha=1, k=2.4, p=2, g=1.3):  # att/sup: energies
    G = lambda e: cl01(e) ** g
    return sig(alpha * logit(clampBase(b)) + k * (pnorm([G(e) for e in sup], p) - pnorm([G(e) for e in att], p)))
def woe_w(e, emax=0.7): return -math.log(1 - min(max(e, 0), emax))
def woe(b, att, sup, alpha=1, k=1.2, p=2):
    return sig(alpha * logit(clampBase(b)) + k * (pnorm([woe_w(e) for e in sup], p) - pnorm([woe_w(e) for e in att], p)))
def jnb_energy(s, c, K=0.7, r=0.0, smax=0.8):
    s = min(max(s, 0), smax); lr = ((1 + s) / (1 - s)) ** K; c = cl01(c)
    return math.log(c * lr + (1 - c) * lr ** (-r))
def jnb(b, att, sup, alpha=1, p=2):  # energies already jnb energies
    pro = [e for e in sup if e >= 0] + [-e for e in att if e < 0]
    con = [e for e in att if e >= 0] + [-e for e in sup if e < 0]
    return sig(alpha * logit(clampBase(b)) + pnorm(pro, p) - pnorm(con, p))
def mlp(b, att, sup): return sig(logit(clampBase(b)) + sum(sup) - sum(att))
def dfquad(b, att, sup):
    ps = lambda xs: 1 - math.prod(1 - cl01(x) for x in xs)
    es, ea = ps(sup), ps(att); b = clampBase(b)
    return b + (1 - b) * (es - ea) if es >= ea else b - b * (ea - es)

# LL generic: claims = list of (weight w_i >=0, kappa dict). score_k = log pi_k - k*||w(1-kappa)||_p
def LL(claims, pi, p, k):
    sc = {}
    for c in pi:
        pen = [w * (1 - kp.get(c, 1.0)) for w, kp in claims]
        sc[c] = math.log(pi[c]) - k * pnorm([x for x in pen if x > 0], p)
    m = max(sc.values()); z = {c: math.exp(v - m) for c, v in sc.items()}; t = sum(z.values())
    return {c: v / t for c, v in z.items()}

LAYERS = {
    # name: (binary fn over energies, energy(s,c), weight(energy) for LL, p, k)
    "wlo": (wlo, energy_prod, lambda e: cl01(e) ** 1.3, 2, 2.4),
    "woe": (woe, energy_prod, woe_w, 2, 1.2),
    "jnb": (jnb, jnb_energy, lambda e: e, 2, 1.0),
    "mlp": (mlp, energy_prod, lambda e: e, 1, 1.0),
}

def c1(seed, n=4000, maxargs=8, extreme=False, open_world=False, base_range=(0.001, 0.999)):
    rnd = random.Random(seed); worst = {}
    for _ in range(n):
        b = rnd.uniform(*base_range)
        def arg():
            if extreme and rnd.random() < 0.4: return (rnd.choice([0, 1, 0.999, 0.7, 0.8]), rnd.choice([0, 1, 0.999]))
            return (rnd.random(), rnd.random())
        sup = [arg() for _ in range(rnd.randint(0, maxargs))]
        att = [arg() for _ in range(rnd.randint(0, maxargs))]
        for name, (f, en, w, p, k) in LAYERS.items():
            es = [en(s, c) for s, c in sup]; ea = [en(s, c) for s, c in att]
            ref = f(b, ea, es)
            claims = [(w(e), {"A": 1, "B": 0}) for e in es] + [(w(e), {"A": 0, "B": 1}) for e in ea]
            if open_world:
                pi = {"A": b, "B": 1 - b, "O": 0.05}; t = sum(pi.values()); pi = {x: v / t for x, v in pi.items()}
                got = LL(claims, pi, p, k)["A"]
            else:
                got = LL(claims, {"A": b, "B": 1 - b}, p, k)["A"]
            worst[name] = max(worst.get(name, 0), abs(got - ref))
    return worst

def c2(seed=3, n=4000):
    rnd = random.Random(seed); one = 0; two = 0; ex = None
    for _ in range(n):
        b = rnd.uniform(0.01, 0.99)
        sup = [rnd.random() for _ in range(rnd.randint(0, 5))]; att = [rnd.random() for _ in range(rnd.randint(0, 5))]
        claims = [(e, {"A": 1, "B": 0}) for e in sup] + [(e, {"A": 0, "B": 1}) for e in att]
        d = abs(C.dempster(claims, {"A": b, "B": 1 - b})["A"] - dfquad(b, att, sup))
        if not sup or not att: one = max(one, d)
        else:
            if d > two: two, ex = d, (b, sup, att)
    return one, two, ex

def c3():
    p = 0.7
    t1 = C.softmax({"A": logit(p), "B": logit(1 - p)})["A"]
    t2 = C.softmax({"A": logit(p) / 2, "B": logit(1 - p) / 2})["A"]
    pr = C.softmax({k: logit(v) for k, v in {"r": .15, "c": .2, "f": .7}.items()})
    return t1, sig(2 * logit(p)), t2, pr

if __name__ == "__main__":
    print("## C1 K=2 closed world, faithful Kotlin ports, LL built per layer")
    for seed in (1, 2, 99):
        print(" seed", seed, {k: f"{v:.2e}" for k, v in c1(seed).items()})
    print(" extreme values (0/1/.999/clamp edges), up to 20 args:", {k: f"{v:.2e}" for k, v in c1(5, maxargs=20, extreme=True).items()})
    print(" base in [1e-6,0.01] (below Kotlin clampBase):", {k: f"{v:.3f}" for k, v in c1(6, base_range=(1e-6, 0.01)).items()})
    print(" OPEN world (Omega=.05 unjudged):", {k: f"{v:.3f}" for k, v in c1(7, open_world=True).items()})
    print("\n## C2 Dempster+BetP vs DF-QuAD")
    one, two, ex = c2(); print(f" one-sided max diff {one:.2e}; two-sided max diff {two:.3f} at b={ex[0]:.2f} sup={[round(x,2) for x in ex[1]]} att={[round(x,2) for x in ex[2]]}")
    print("\n## C3 model A")
    t1, s2, t2, pr = c3(); print(f" T=1 share {t1:.4f} vs sigmoid(2 logit .7) {s2:.4f}; T=2 share {t2:.4f}; prior {[round(pr[k],3) for k in 'rcf']}")
