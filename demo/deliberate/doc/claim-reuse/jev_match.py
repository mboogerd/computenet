"""Precision stage, variant 1: one 4-way Jev Choice per sampled pair.

  bare: state holds only the two claim texts
  ctx:  plus each claim's question and the parent it argues about
Writes data/jev_<variant>.json: {pair: {"label", "usage"}}.
Run: TYPESAFE_API_KEY=... python3 jev_match.py bare ctx
"""
import sys
from concurrent.futures import ThreadPoolExecutor
import common

CRITERIA = {
    "SAME": "Same proposition: both assert the same thing about the same subject and scope (a reworded "
            "paraphrase counts), so evidence for one is evidence for the other to the same degree, and one "
            "credence could serve both.",
    "CONTEXT_DEPENDENT": "Same wording or near-same wording, but in their contexts they assert different things "
                         "(for example a pronoun, an implicit subject, or 'this' refers to something different), so "
                         "one could be true while the other is false.",
    "OVERLAP": "Related but not the same proposition: one is broader or narrower than the other, one is an "
               "instance of or evidence for the other, or they share a topic but make different assertions.",
    "DIFFERENT": "Different propositions that merely share vocabulary or topic.",
}
QUESTION = ("Do `claim_a` and `claim_b` assert the same proposition, so that a single judgement of how likely it is "
            "to be true, and a single analysis of the arguments for and against it, could be shared by both?")


def state(p, variant):
    if variant == "bare":
        return {"claim_a": p["A"]["claim"], "claim_b": p["B"]["claim"]}
    return {"claim_a": p["A"]["claim"], "claim_a_question": p["A"]["question"], "claim_a_argues_about": p["A"]["parent"],
            "claim_b": p["B"]["claim"], "claim_b_question": p["B"]["question"], "claim_b_argues_about": p["B"]["parent"]}


def ask(p, variant):
    try:
        answers, usage = common.jev(state(p, variant), {"eq": {"type": "choice", "instructions": {"question": QUESTION},
                                                             "criteria": CRITERIA}})
        return {"label": answers["eq"]["choice"], "usage": usage}
    except Exception as e:  # noqa: BLE001 - experiment script: record and move on
        return {"error": repr(e)}


if __name__ == "__main__":
    sample = common.expand(common.load("sample.json"), common.load_claims())
    for variant in sys.argv[1:]:
        with ThreadPoolExecutor(6) as ex:
            res = list(ex.map(lambda p: ask(p, variant), sample))
        common.save({p["pair"]: r for p, r in zip(sample, res)}, f"jev_{variant}.json")
        print(variant, "errors", sum("error" in r for r in res), file=sys.stderr)
