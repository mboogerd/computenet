"""Experiment g, step 1: graded belief w that each claim of a pair implies the other (both directions).

One Jev request per pair with two Scores over five levels, mapped to [0,1] (JevJudge.scoreOf).
Writes data/implication_belief.json: {pair: {"w_ab", "w_ba"}}.
Run: TYPESAFE_API_KEY=... python3 implication_belief.py
"""
import sys
from concurrent.futures import ThreadPoolExecutor
import common

LEVELS = [
    "Certainly not: `{y}` could easily be false while `{x}` is true.",
    "Probably not: `{y}` asserts something substantial that `{x}` does not establish.",
    "Uncertain: whether `{x}` guarantees `{y}` depends on how the wording is read.",
    "Probably: `{x}` guarantees `{y}` under the natural reading, though a strict reading finds a small gap.",
    "Certainly: if `{x}` is true, `{y}` must be true; `{y}` adds nothing beyond `{x}`.",
]


def score(x, y):
    return {"type": "score",
            "instructions": f"How certain is it that if `{x}` is true, `{y}` is also true — taking each as worded, "
                            f"including every qualifier, number, date, scope and example it names?",
            "criteria": [lv.format(x=x, y=y) for lv in LEVELS]}


def ask(p):
    try:
        answers, _ = common.jev({"claim_a": p["A"]["claim"], "claim_b": p["B"]["claim"]},
                                {"ab": score("claim_a", "claim_b"), "ba": score("claim_b", "claim_a")})
        return {"w_ab": answers["ab"]["score"] / 4, "w_ba": answers["ba"]["score"] / 4}
    except Exception as e:  # noqa: BLE001 - experiment script: record and move on
        return {"error": repr(e)}


if __name__ == "__main__":
    pairs = common.expand(common.load("all80.json"), common.load_claims())
    with ThreadPoolExecutor(6) as ex:
        res = list(ex.map(ask, pairs))
    common.save({p["pair"]: r for p, r in zip(pairs, res)}, "implication_belief.json")
    print(len(res), "pairs, errors", sum("error" in r for r in res), file=sys.stderr)
