#!/usr/bin/env python3
"""
Multi-class compatibility pilot for Jev (bead computenet-1ow0x).

Which per-claim judgment shape can Jev do reliably when a question has K answer
classes?  Shapes (all asked of ONE argument claim, assumed true):

  compat  Noul   per class  "could <class> still be the answer?"          (idea 3, 0/1 subset)
  elim    Noul   per class  "does the claim rule <class> out?"            (elimination; negation check on compat)
  lik     Score5 per class  "if <class> were the answer, how expected is the claim?"  (idea 1, likelihood)
  bear    Score5 per class  signed bearing: rules out .. against .. none .. for .. settles  (graded idea 3)
  pair    Choice per pair   "favours A over B, B over A, or neither?"     (idea 2)
  choice  Choice over K     "which answer does it favour most?"            (the control that failed in dq2fy.29)
  prior   Choice + Noul per class, no claim: Jev's own first impression (leakage baseline; replicates the 1.28-sum pilot)

Arms (request layouts):
  ISO    state {root_question, claim}; one question per class per shape, the class
         only in that question's instructions (no alternatives visible). 1 request per claim x paraphrase.
  CTX    state adds `candidate_answers` (rotated order, K rotations) - alternatives visible; adds `choice`.
  PAIR   all unordered pairs, both orders, as independent Choice questions in one request.
  SINGLE Q4 only, one question per request: checks that packing questions into one request changes nothing.
  PRIOR  per root question.

Usage (NOTHING is sent unless --live is given):
  python3 pilot.py plan                      # build requests.jsonl, print counts + token/cost estimate (free)
  python3 pilot.py run --live --max-usd 2    # PAID: needs TYPESAFE_API_KEY; resumable, appends responses.jsonl
  python3 pilot.py analyze                   # metrics + pre-registered decision from responses.jsonl
Stdlib only.
"""
import argparse
import itertools
import json
import os
import random
import statistics
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
TESTSET = HERE / "testset.json"
REQUESTS = HERE / "requests.jsonl"
RESPONSES = HERE / "responses.jsonl"
ENDPOINT = "https://api.typesafe.ai/v1/systemone"
MODEL = "jev-latest"
# demo/deliberate Cost.kt JEV_DEFAULT (third-party listing, assumed): $0.042 / 1M input, output free.
USD_PER_M_INPUT = 0.042
REF_RANK = {"--": 0, "-": 1, "0": 2, "+": 3, "++": 4}

# ---------------------------------------------------------------- question formats

def noul(instructions, yes, no):
    return {"type": "noul", "instructions": instructions, "criteria": {"true": yes, "false": no}}

def score(instructions, levels):
    return {"type": "score", "instructions": instructions, "criteria": levels}

def choice(instructions, criteria):
    return {"type": "choice", "instructions": instructions, "criteria": criteria}

PARAPHRASES = ("A", "B")

def compat_q(cand, p, extra=None):
    q = {
        "A": "Suppose `claim` is true. Could `candidate_answer` still be the correct answer to `root_question`? "
             "Answer no only if `claim` being true would rule `candidate_answer` out. A claim that merely makes "
             "it less likely, or says nothing about it, leaves it possible.",
        "B": "Can `claim` and `candidate_answer` both be true together, that is, `claim` true and "
             "`candidate_answer` the correct answer to `root_question`? Answer no only if they contradict each other.",
    }[p]
    return noul(
        {"candidate_answer": cand, **(extra or {}), "question": q},
        yes="Still possible: even with `claim` true, `candidate_answer` could be the correct answer.",
        no="Ruled out: if `claim` is true, `candidate_answer` cannot be the correct answer, except under exceptional conditions.",
    )

def elim_q(cand, p, extra=None):
    q = {
        "A": "Suppose `claim` is true. Would that rule out `candidate_answer` as the correct answer to `root_question`?",
        "B": "Do `claim` and the answer `candidate_answer` (to `root_question`) contradict each other, so that they cannot both be true?",
    }[p]
    return noul(
        {"candidate_answer": cand, **(extra or {}), "question": q},
        yes="Ruled out: if `claim` is true, `candidate_answer` cannot be the correct answer, except under exceptional conditions.",
        no="Not ruled out: `claim` being true leaves `candidate_answer` possible, even if less likely.",
    )

LIK_LEVELS = [
    "Nearly impossible: in a world where `candidate_answer` is the correct answer, `claim` would almost certainly be false.",
    "Unexpected: in that world `claim` would more likely be false than true.",
    "As likely as not: in that world `claim` would be about equally likely to be true or false.",
    "Expected: in that world `claim` would more likely be true than false.",
    "Nearly certain: in a world where `candidate_answer` is the correct answer, `claim` would almost certainly be true.",
]

def lik_q(cand, p, extra=None):
    q = {
        "A": "Suppose `candidate_answer` is in fact the correct answer to `root_question`. How expected would it then "
             "be that `claim` is true? Judge only this conditional; do not judge whether `candidate_answer` is actually correct.",
        "B": "In a world where the correct answer to `root_question` is `candidate_answer`, how likely is `claim` to be "
             "true? Do not judge whether `candidate_answer` is actually the correct answer.",
    }[p]
    return score({"candidate_answer": cand, **(extra or {}), "question": q}, LIK_LEVELS)

BEAR_LEVELS = [
    "Rules it out: with `claim` true, `candidate_answer` could not be the correct answer except under exceptional conditions.",
    "Counts against it: `claim` makes `candidate_answer` less likely to be the correct answer, without ruling it out.",
    "No bearing: `claim` makes `candidate_answer` neither more nor less likely to be the correct answer.",
    "Counts for it: `claim` makes `candidate_answer` more likely to be the correct answer, without settling it.",
    "Settles it: with `claim` true, `candidate_answer` would be a correct answer except under exceptional conditions.",
]

def bear_q(cand, p, extra=None):
    q = {
        "A": "Suppose `claim` is true. How does that bear on `candidate_answer` as the answer to `root_question`? "
             "Rate only the bearing of `claim` on `candidate_answer`, not whether `claim` is true and not how likely "
             "`candidate_answer` is overall.",
        "B": "If `claim` is true, is `candidate_answer` a better or a worse answer to `root_question` than it would "
             "otherwise be? Rate only what `claim` changes, not how good `candidate_answer` is overall.",
    }[p]
    return score({"candidate_answer": cand, **(extra or {}), "question": q}, BEAR_LEVELS)

def pair_q(a_id, a_txt, b_id, b_txt, p):
    q = {
        "A": "Suppose `claim` is true. Does it favour `first_answer` over `second_answer` as the answer to "
             "`root_question`, favour `second_answer` over `first_answer`, or neither?",
        "B": "Taking `claim` as true, which of `first_answer` and `second_answer` does it make the better answer to "
             "`root_question`, if either?",
    }[p]
    return choice(
        {"first_answer": a_txt, "second_answer": b_txt, "question": q},
        {
            f"FAVOURS_{a_id}": "It makes `first_answer` more likely to be correct relative to `second_answer`.",
            f"FAVOURS_{b_id}": "It makes `second_answer` more likely to be correct relative to `first_answer`.",
            "NEITHER": "It bears on both equally, or on neither.",
        },
    )

def choice_q(classes_in_order, p):
    q = {
        "A": "Suppose `claim` is true. Which answer to `root_question` does it favour most? "
             "Answer NO_PREFERENCE if it favours no answer over the others.",
        "B": "Taking `claim` as true, which one of the answers to `root_question` does it support best? "
             "Answer NO_PREFERENCE if it does not single one out.",
    }[p]
    crit = {cid: txt for cid, txt in classes_in_order}
    crit["NO_PREFERENCE"] = "`claim` favours no answer over the others."
    return choice({"question": q}, crit)

def prior_qs(question, p):
    qs = {}
    order = list(question["classes"].items())
    qs["prior_choice"] = choice(
        {"question": {"A": "Which is the correct answer to `root_question`?",
                      "B": "What is the best answer to `root_question`?"}[p]},
        dict(order),
    )
    for cid, txt in order:
        qs[f"prior_noul|{cid}"] = noul(
            {"candidate_answer": txt,
             "question": {"A": "Is `candidate_answer` the correct answer to `root_question`?",
                          "B": "Is `candidate_answer` a true answer to `root_question`?"}[p]},
            yes="Correct.", no="Not correct.",
        )
    return qs

# ---------------------------------------------------------------- request plan

def none_extra(question, cid):
    """NONE is defined relative to the others, so its instructions always name them."""
    if cid != "NONE":
        return None
    return {"the_other_answers": [t for c, t in question["classes"].items() if c != "NONE"]}

PER_CLASS = {"compat": compat_q, "elim": elim_q, "lik": lik_q, "bear": bear_q}

def build_plan():
    ts = json.loads(TESTSET.read_text())
    reqs = []
    for q in ts["questions"]:
        classes = list(q["classes"].items())
        K = len(classes)
        for p in PARAPHRASES:
            reqs.append({"rid": f"PRIOR|{q['id']}|{p}", "arm": "PRIOR", "qid": q["id"], "cid": None, "para": p, "perm": 0,
                         "state": {"root_question": q["root_question"]}, "questions": prior_qs(q, p)})
        for c in q["claims"]:
            base = {"root_question": q["root_question"], "claim": c["text"]}
            for p in PARAPHRASES:
                # ISO: no alternatives in state
                qs = {f"{s}|{cid}": fn(txt, p, none_extra(q, cid)) for s, fn in PER_CLASS.items() for cid, txt in classes}
                reqs.append({"rid": f"ISO|{c['id']}|{p}", "arm": "ISO", "qid": q["id"], "cid": c["id"], "para": p, "perm": 0,
                             "state": base, "questions": qs})
                # CTX: alternatives visible, K rotations so every class sits in every position once
                for r in range(K):
                    rot = classes[r:] + classes[:r]
                    st = {**base, "candidate_answers": [t for _, t in rot]}
                    qs = {f"{s}|{cid}": fn(txt, p) for s, fn in PER_CLASS.items() for cid, txt in rot}
                    qs["choice"] = choice_q(rot, p)
                    reqs.append({"rid": f"CTX|{c['id']}|{p}|{r}", "arm": "CTX", "qid": q["id"], "cid": c["id"], "para": p,
                                 "perm": r, "state": st, "questions": qs})
                # PAIR: every unordered pair in both orders
                qs = {}
                for (a, at), (b, bt) in itertools.combinations(classes, 2):
                    qs[f"pair|{a}>{b}"] = pair_q(a, at, b, bt, p)
                    qs[f"pair|{b}>{a}"] = pair_q(b, bt, a, at, p)
                reqs.append({"rid": f"PAIR|{c['id']}|{p}", "arm": "PAIR", "qid": q["id"], "cid": c["id"], "para": p, "perm": 0,
                             "state": base, "questions": qs})
            if q["id"] == "Q4":  # packing check, paraphrase A only
                for s, fn in PER_CLASS.items():
                    for cid, txt in classes:
                        reqs.append({"rid": f"SINGLE|{c['id']}|{s}|{cid}", "arm": "SINGLE", "qid": q["id"], "cid": c["id"],
                                     "para": "A", "perm": 0, "state": base, "questions": {f"{s}|{cid}": fn(txt, "A")}})
    return ts, reqs

def body_of(r):
    return {"model": MODEL, "state": r["state"], "questions": r["questions"]}

# ---------------------------------------------------------------- plan / run

def cmd_plan(_):
    _, reqs = build_plan()
    with REQUESTS.open("w") as f:
        for r in reqs:
            f.write(json.dumps(r) + "\n")
    by_arm = {}
    for r in reqs:
        n, qn, tok = by_arm.get(r["arm"], (0, 0, 0))
        # rough: 1 token ~ 4 chars of the JSON body, plus 150 tokens assumed per-request overhead
        by_arm[r["arm"]] = (n + 1, qn + len(r["questions"]), tok + len(json.dumps(body_of(r))) / 4 + 150)
    tot = [sum(v[i] for v in by_arm.values()) for i in range(3)]
    print(f"{'arm':8} {'requests':>9} {'questions':>10} {'~input tokens':>14} {'~USD':>8}")
    for a, (n, qn, tok) in by_arm.items():
        print(f"{a:8} {n:9d} {qn:10d} {tok:14.0f} {tok * USD_PER_M_INPUT / 1e6:8.4f}")
    print(f"{'TOTAL':8} {tot[0]:9d} {tot[1]:10d} {tot[2]:14.0f} {tot[2] * USD_PER_M_INPUT / 1e6:8.4f}")
    print(f"(rate ${USD_PER_M_INPUT}/1M input, output free: Cost.kt JEV_DEFAULT, third-party listing, assumed)")
    print(f"wrote {REQUESTS}")

def post(body, key, attempts=4):
    data = json.dumps(body).encode()
    for i in range(attempts):
        req = urllib.request.Request(ENDPOINT, data=data, method="POST", headers={
            "Authorization": f"Bearer {key}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.loads(resp.read())
        except urllib.error.HTTPError as e:
            if e.code in (429, 529) and i < attempts - 1:
                time.sleep(0.5 * 2 ** i)
                continue
            raise

def cmd_run(a):
    if not a.live:
        sys.exit("refusing: pass --live to make PAID Jev calls (use `plan` for a free dry run)")
    key = os.environ.get("TYPESAFE_API_KEY") or sys.exit("TYPESAFE_API_KEY is not set")
    _, reqs = build_plan()
    done = set()
    if RESPONSES.exists():
        done = {json.loads(l)["rid"] for l in RESPONSES.open()}
    todo = [r for r in reqs if r["rid"] not in done and (not a.arms or r["arm"] in a.arms)]
    random.Random(7).shuffle(todo)  # no systematic time-of-call confound between arms
    spent_tokens = 0
    out = RESPONSES.open("a")

    def one(r):
        return r, post(body_of(r), key)

    with ThreadPoolExecutor(a.concurrency) as ex:
        for r, resp in ex.map(one, todo):
            spent_tokens += (resp.get("usage") or {}).get("input_tokens", 0)
            out.write(json.dumps({"rid": r["rid"], "response": resp}) + "\n")
            out.flush()
            if spent_tokens * USD_PER_M_INPUT / 1e6 > a.max_usd:
                sys.exit(f"stopping: spend cap ${a.max_usd} reached")
    print(f"done; {spent_tokens} input tokens, ~${spent_tokens * USD_PER_M_INPUT / 1e6:.4f}")

# ---------------------------------------------------------------- analysis

def mean(xs):
    xs = list(xs)
    return sum(xs) / len(xs) if xs else float("nan")

def pearson(x, y):
    if len(x) < 3:
        return float("nan")
    mx, my = mean(x), mean(y)
    sx = sum((a - mx) ** 2 for a in x) ** 0.5
    sy = sum((b - my) ** 2 for b in y) ** 0.5
    return sum((a - mx) * (b - my) for a, b in zip(x, y)) / (sx * sy) if sx and sy else float("nan")

def kappa(pred, gold):
    n = len(pred)
    if not n:
        return float("nan")
    po = sum(p == g for p, g in zip(pred, gold)) / n
    p1, g1 = sum(pred) / n, sum(gold) / n
    pe = p1 * g1 + (1 - p1) * (1 - g1)
    return (po - pe) / (1 - pe) if pe < 1 else float("nan")

def value(shape, ans):
    """Map an answer to [0,1], higher = more favourable to the class."""
    if shape == "compat":
        return ans["noul"]
    if shape == "elim":
        return 1 - ans["noul"]
    if shape in ("lik", "bear"):
        return ans["score"] / 4
    raise ValueError(shape)

def ruled_out(shape, ans):
    if shape == "compat":
        return ans["noul"] < 0.5
    if shape == "elim":
        return ans["noul"] > 0.5
    return ans["probabilities"].get("0", 0) >= 0.5  # lik / bear: level 0

def cmd_analyze(a):
    ts, reqs = build_plan()
    src = Path(a.responses) if getattr(a, "responses", None) else RESPONSES
    meta = {r["rid"]: r for r in reqs}
    answers = {}
    usage = {}
    for line in src.open():
        rec = json.loads(line)
        answers[rec["rid"]] = rec["response"]["answers"]
        arm = meta[rec["rid"]]["arm"]
        usage[arm] = usage.get(arm, 0) + (rec["response"].get("usage") or {}).get("input_tokens", 0)
    refs, contested, claims_of = {}, set(), {}
    for q in ts["questions"]:
        for c in q["claims"]:
            claims_of[c["id"]] = (q, c)
            for cid, cell in c["ref"].items():
                refs[(c["id"], cid)] = cell
            for cid in c.get("contested", {}):
                contested.add((c["id"], cid))
    clean = lambda k: k not in contested

    # prior
    prior = {}
    for rid, ans in answers.items():
        if meta[rid]["arm"] == "PRIOR":
            for k, v in ans.items():
                if k.startswith("prior_noul|"):
                    prior.setdefault((meta[rid]["qid"], k.split("|")[1]), []).append(v["noul"])
    prior = {k: mean(v) for k, v in prior.items()}

    report, verdict = {}, {}
    for arm in ("ISO", "CTX", "SINGLE"):
        for shape in PER_CLASS:
            reps = {}  # (claim, class) -> list of (para, perm, ans)
            for rid, ans in answers.items():
                m = meta[rid]
                if m["arm"] != arm:
                    continue
                for k, a in ans.items():
                    s, _, cid = k.partition("|")
                    if s == shape:
                        reps.setdefault((m["cid"], cid), []).append((m["para"], m["perm"], a))
            if not reps:
                continue
            v = {k: mean(value(shape, a) for _, _, a in xs) for k, xs in reps.items()}
            # G1 pairwise order accuracy within claim, uncontested, ref ranks differ
            hits, n, tie_ok, tie_n = 0.0, 0, 0, 0
            for cl in {k[0] for k in v}:
                ks = [k for k in v if k[0] == cl and clean(k)]
                for x, y in itertools.combinations(ks, 2):
                    rx, ry = REF_RANK[refs[x]], REF_RANK[refs[y]]
                    d = v[x] - v[y]
                    if rx == ry:
                        tie_n += 1
                        tie_ok += abs(d) < 0.15
                    else:
                        n += 1
                        hits += 0.5 if abs(d) < 0.02 else float((d > 0) == (rx > ry))
            poa = hits / n if n else float("nan")
            # G2 rule-out kappa (majority over replicates)
            keys = [k for k in reps if clean(k)]
            pred = [mean(ruled_out(shape, a) for _, _, a in reps[k]) > 0.5 for k in keys]
            gold = [refs[k] == "--" for k in keys]
            kap = kappa(pred, gold)
            # G3 neutral false-move rate on ref '0' cells
            zero = [k for k in keys if refs[k] == "0"]
            if shape in ("compat", "elim"):
                moved = [v[k] < 0.5 for k in zero]
            elif shape == "bear":
                moved = [abs(v[k] - 0.5) >= 0.25 for k in zero]
            else:
                moved = []
            fmr = mean(moved) if moved else float("nan")
            # G4 stability: spread across replicates, and flips across the 0.5 threshold
            spreads, flips = [], []
            for k, xs in reps.items():
                vals = [value(shape, a) for _, _, a in xs]
                if len(vals) > 1:
                    spreads.append(max(vals) - min(vals))
                    # Noul: replicates straddle 0.5; Score: replicates differ by a full level or more
                    flips.append(max(vals) - min(vals) >= 0.25 if shape in ("lik", "bear") else min(vals) < 0.5 < max(vals))
            # G5 overlap (Q4): MAMMAL evidence must not move MARINE
            ov = [v.get((c, "MARINE")) for c in ("d1", "d2", "d8") if (c, "MARINE") in v]
            g5 = all((x >= 0.7) if shape in ("compat", "elim") else (0.375 <= x <= 0.625) for x in ov) if ov and shape != "lik" else None
            # leakage: on ref-0 cells, does the judged value track Jev's own prior of the class?
            lx = [prior.get((claims_of[k[0]][0]["id"], k[1])) for k in zero]
            leak = pearson([x for x in lx if x is not None], [v[k] for k, x in zip(zero, lx) if x is not None])
            # calibration of compat/elim vs binary compat ref
            brier = mean((v[k] - (refs[k] != "--")) ** 2 for k in keys) if shape in ("compat", "elim") else float("nan")
            report[(arm, shape)] = dict(poa=poa, ties=tie_ok / tie_n if tie_n else float("nan"), kappa=kap, fmr=fmr,
                                        spread=mean(spreads), flip=mean(flips), overlap=g5, leak=leak, brier=brier)

    # coherence compat vs elim
    for arm in ("ISO", "CTX"):
        diffs = []
        for rid, ans in answers.items():
            if meta[rid]["arm"] == arm:
                for k, a in ans.items():
                    if k.startswith("compat|"):
                        e = ans.get("elim|" + k.split("|")[1])
                        if e:
                            diffs.append(abs(a["noul"] + e["noul"] - 1))
        report[(arm, "coherence")] = mean(diffs)

    # pairwise
    pv = {}
    for rid, ans in answers.items():
        if meta[rid]["arm"] == "PAIR":
            cl = meta[rid]["cid"]
            for k, a in ans.items():
                x, y = k.split("|")[1].split(">")
                pr = a["probabilities"]
                pv.setdefault((cl, x, y), []).append(pr.get(f"FAVOURS_{x}", 0) - pr.get(f"FAVOURS_{y}", 0))
                pv.setdefault((cl, y, x), []).append(pr.get(f"FAVOURS_{y}", 0) - pr.get(f"FAVOURS_{x}", 0))
    hits, n, cons = 0.0, 0, []
    for (cl, x, y), ds in pv.items():
        if x < y and clean((cl, x)) and clean((cl, y)):
            rx, ry = REF_RANK[refs[(cl, x)]], REF_RANK[refs[(cl, y)]]
            d = mean(ds)
            if rx != ry:
                n += 1
                hits += 0.5 if abs(d) < 0.02 else float((d > 0) == (rx > ry))
        bucket = lambda d: (d > 0.05) - (d < -0.05)
        cons.append(len({bucket(d) for d in ds}) == 1)
    report[("PAIR", "pair")] = dict(poa=hits / n if n else float("nan"), order_consistency=mean(cons))

    # choice control: degeneracy
    ch = {}
    for rid, ans in answers.items():
        if meta[rid]["arm"] == "CTX":
            ch.setdefault(meta[rid]["cid"], []).append(ans["choice"]["probabilities"])
    spike = mean(mean(max(p.values()) >= 0.8 for p in ps) for ps in ch.values()) if ch else float("nan")
    neutral_claims = [cl for cl in ch if all(refs[(cl, k)] == "0" for k in claims_of[cl][0]["classes"])]
    nopref = mean(mean(p.get("NO_PREFERENCE", 0) for p in ch[cl]) for cl in neutral_claims) if neutral_claims else float("nan")
    report[("CTX", "choice")] = dict(spike_rate=spike, no_pref_on_neutral=nopref)

    for k, r in report.items():
        print(k, json.dumps(r, default=str) if isinstance(r, dict) else round(r, 3))
    print("input tokens by arm:", usage, "~USD", round(sum(usage.values()) * USD_PER_M_INPUT / 1e6, 4))
    decide(report)

def passes(r, need):
    ok = lambda x, f: x is not None and x == x and f(x)
    checks = {
        "G1": ok(r.get("poa"), lambda x: x >= 0.85),
        "G2": ok(r.get("kappa"), lambda x: x >= 0.6),
        "G3": ok(r.get("fmr"), lambda x: x <= 0.20),
        "G4": ok(r.get("flip"), lambda x: x <= 0.10) and ok(r.get("spread"), lambda x: x <= 0.25),
        "G5": r.get("overlap") is True,
    }
    return all(checks[g] for g in need), checks

def decide(rep):
    """Pre-registered decision rule (see report). Prints the verdict; changes nothing else."""
    print("\n--- decision ---")
    out = {}
    for arm in ("ISO", "CTX"):
        for shape, need in (("bear", ["G1", "G2", "G3", "G4", "G5"]), ("compat", ["G2", "G3", "G4", "G5"]),
                            ("elim", ["G2", "G3", "G4", "G5"]), ("lik", ["G1", "G4"])):
            if (arm, shape) in rep:
                ok, checks = passes(rep[(arm, shape)], need)
                out[(arm, shape)] = ok
                print(f"{arm:4} {shape:7} {'PASS' if ok else 'fail'}  {checks}")
    best_arm = "ISO"
    if out.get(("CTX", "bear")) and rep[("CTX", "bear")]["poa"] >= rep.get(("ISO", "bear"), {}).get("poa", 0) + 0.05:
        best_arm = "CTX"
    if out.get((best_arm, "bear")) or out.get(("ISO", "bear")):
        print(f"ADOPT graded per-class bearing (bear, {best_arm}); rule-out = level 0 gives the 0/1 subset for free.")
    elif out.get(("ISO", "compat")) or out.get(("CTX", "compat")):
        print("ADOPT 0/1 compatibility Noul (compat); graded bearing not reliable.")
    else:
        print("NO per-class shape passes: keep model A (independent binary roots + softmax); do not build idea 3 on Jev.")
    lik = rep.get((best_arm, "lik"), {})
    bear = rep.get((best_arm, "bear"), {})
    if out.get((best_arm, "lik")) and lik.get("poa", 0) >= bear.get("poa", 0) + 0.05:
        print("ALSO: likelihood Score beats bearing by >= 0.05 POA - consider idea 1 (Bayes) as a layer.")
    pr = rep.get(("PAIR", "pair"), {})
    if pr.get("poa", 0) >= bear.get("poa", 0) + 0.05 and pr.get("order_consistency", 0) >= 0.9:
        print("ALSO: pairwise beats per-class by >= 0.05 POA with order consistency >= 0.9 - pairwise worth its K(K-1) cost.")
    ch = rep.get(("CTX", "choice"), {})
    if ch.get("spike_rate", 0) >= 0.5 or ch.get("no_pref_on_neutral", 1) < 0.5:
        print("Choice control DEGENERATE (confirms dq2fy.29): spike_rate %.2f, NO_PREFERENCE on neutral claims %.2f"
              % (ch.get("spike_rate", float('nan')), ch.get("no_pref_on_neutral", float('nan'))))

def cmd_selftest(_):
    """FREE: synthesise answers from the reference (+ noise) and run the analysis on them - checks the plumbing only."""
    ts, reqs = build_plan()
    rng = random.Random(1)
    refs = {(c["id"], k): v for q in ts["questions"] for c in q["claims"] for k, v in c["ref"].items()}
    lvl = lambda cell: max(0, min(4, REF_RANK[cell] + rng.choice([0, 0, 0, 1, -1])))
    def sc(l):
        return {"type": "score", "score": float(l), "probabilities": {str(i): float(i == l) for i in range(5)}}
    path = HERE / "responses.selftest.jsonl"
    with path.open("w") as f:
        for r in reqs:
            ans = {}
            for k, qq in r["questions"].items():
                s, _, rest = k.partition("|")
                if s == "prior_noul":
                    ans[k] = {"type": "noul", "noul": rng.random()}
                elif s in ("compat", "elim"):
                    ro = refs[(r["cid"], rest)] == "--"
                    y = (0.1 if ro else 0.9) + rng.uniform(-0.08, 0.08)
                    ans[k] = {"type": "noul", "noul": y if s == "compat" else 1 - y}
                elif s in ("lik", "bear"):
                    ans[k] = sc(lvl(refs[(r["cid"], rest)]))
                else:
                    keys = list(qq["criteria"].keys())
                    w = [rng.random() for _ in keys]
                    ans[k] = {"type": "choice", "choice": keys[0], "probabilities": {kk: x / sum(w) for kk, x in zip(keys, w)}}
            f.write(json.dumps({"rid": r["rid"], "response": {"answers": ans, "usage": {"input_tokens": 1000}}}) + "\n")
    cmd_analyze(argparse.Namespace(responses=str(path)))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--testset", help="reference testset JSON (default testset.json); claims/classes must match for requests to be reused")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("plan")
    r = sub.add_parser("run")
    r.add_argument("--live", action="store_true")
    r.add_argument("--max-usd", type=float, default=2.0)
    r.add_argument("--concurrency", type=int, default=8)
    r.add_argument("--arms", nargs="*")
    an = sub.add_parser("analyze")
    an.add_argument("--responses")
    sub.add_parser("selftest")
    a = ap.parse_args()
    if a.testset:
        global TESTSET
        TESTSET = Path(a.testset).resolve()
    {"plan": cmd_plan, "run": cmd_run, "analyze": cmd_analyze, "selftest": cmd_selftest}[a.cmd](a)

if __name__ == "__main__":
    main()
