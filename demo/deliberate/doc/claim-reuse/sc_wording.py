"""Experiment h: rewordings of the Jev self-containment check, scored against data/sc_gold.json.

Each wording is one Choice (NEEDS_CONTEXT / SELF_CONTAINED) over state {claim}. `orig` is
experiment c's wording (its answers are in sc.json, not re-asked).
Run: TYPESAFE_API_KEY=... python3 sc_wording.py    -> data/sc_wording.json, then the score table
"""
import sys
from concurrent.futures import ThreadPoolExecutor
import common

WORDINGS = {
    "debate": ("Would the truth of `claim` depend on which debate, question or conversation it appears in — that is, does "
               "it only make a definite assertion once you know the surrounding discussion?",
               "Yes: read on its own, `claim` does not say exactly what it asserts; the surrounding discussion would decide it.",
               "No: `claim` asserts the same definite thing wherever it appears."),
    "unnamed": ("Does `claim` refer to something it never names — a pronoun, or 'this', 'that', 'such', 'the law', 'the "
                "study', 'the experiment', 'the credit' — pointing at a specific thing the sentence does not identify?",
                "Yes: it points at a specific thing without naming or identifying it.",
                "No: every specific thing it refers to is named or identified in the sentence. Technical terms, jargon and "
                "well-known named studies, laws or people count as identified."),
    "combined": ("Could a well-informed reader who sees ONLY `claim` identify every specific thing it refers to and tell "
                 "exactly what it asserts? Technical vocabulary, specialist jargon, and named people, studies or laws are "
                 "fine — the question is only whether something is referred to without being identified.",
                 "No: something it refers to (a law, study, experiment, person, policy, or the subject) is left unidentified, "
                 "so its meaning depends on context.",
                 "Yes: everything it refers to is identified, even if the subject is technical."),
}


def ask(item):
    cid, text = item
    qs = {k: {"type": "choice", "instructions": {"question": q}, "criteria": {"NEEDS_CONTEXT": needs, "SELF_CONTAINED": ok}}
          for k, (q, needs, ok) in WORDINGS.items()}
    try:
        answers, _ = common.jev({"claim": text}, qs)
        return {"id": cid, **{k: answers[k]["choice"] for k in WORDINGS}}
    except Exception as e:  # noqa: BLE001 - experiment script: record and move on
        return {"id": cid, "error": repr(e)}


if __name__ == "__main__":
    claims = common.load_claims()
    if "--score-only" not in sys.argv:
        ids = sorted({g["id"] for g in common.load("sc_gold.json")} | {r["id"] for r in common.load("sc.json")})
        with ThreadPoolExecutor(6) as ex:
            res = list(ex.map(ask, [(i, claims[i]["text"]) for i in ids]))
        common.save(res, "sc_wording.json")
    R = {r["id"]: r for r in common.load("sc_wording.json")}
    assert not any("error" in r for r in R.values()), "rerun: some requests failed"
    orig = {r["id"]: r["sc"] for r in common.load("sc.json")}
    G = {g["id"]: g["label"] for g in common.load("sc_gold.json")}
    gp = {i for i in G if G[i] == "NEEDS_CONTEXT"}
    print(f"gold: {len(gp)} of {len(G)} NEEDS_CONTEXT")
    print("wording     precision  recall   flagged over all 1066 claims")
    for k in ["orig", *WORDINGS]:
        ans = orig if k == "orig" else {i: r[k] for i, r in R.items()}
        flag = {i for i in G if ans[i] == "NEEDS_CONTEXT"}
        allf = sum(v == "NEEDS_CONTEXT" for v in ans.values())
        print(f"  {k:9} {len(flag & gp):3}/{len(flag):<4}  {len(flag & gp):3}/{len(gp):<4}  {allf} ({allf / len(ans):.0%})")
    both = {i for i in G if R[i]["unnamed"] == "NEEDS_CONTEXT" and R[i]["combined"] == "NEEDS_CONTEXT"}
    print(f"  unnamed AND combined: {len(both & gp)}/{len(both)} precision, {len(both & gp)}/{len(gp)} recall")
