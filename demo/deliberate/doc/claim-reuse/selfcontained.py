"""Experiment c: is each claim self-contained, and does judging it without the question move its plausibility?

Per claim, two Jev requests:
  bare: state {claim}                 -> `sc` Choice (SELF_CONTAINED / NEEDS_CONTEXT) + `p` plausibility
  q:    state {root_question, claim}  -> `p` plausibility with today's CRED-01 instruction (JevJudge)
The model D `knowledge` Choice is NOT asked, so a claim outside Jev's knowledge gets its raw score.
Run: TYPESAFE_API_KEY=... python3 selfcontained.py            -> data/sc.json (every claim)
     TYPESAFE_API_KEY=... python3 selfcontained.py rewrites   -> data/sc_rewrites_out.json (sc_gold.json rewrites)
"""
import sys
from concurrent.futures import ThreadPoolExecutor
import common

LEVELS = [  # JevJudge.PLAUSIBILITY_LEVELS
    "Almost certainly false: available facts or well-established knowledge directly contradict the claim; it would require exceptional contrary evidence to be true.",
    "Probably false: available evidence or well-established knowledge weighs against the claim, although the claim remains reasonably possible.",
    "Uncertain: evidence is absent, balanced, or conflicting, so neither truth nor falsity is more likely.",
    "Probably true: available evidence or well-established knowledge supports the claim, although meaningful uncertainty remains.",
    "Almost certainly true: available facts or well-established knowledge directly support the claim; it would require exceptional contrary evidence to be false.",
]
P_Q = ("How likely is `claim` to be true? Judge only what `claim` itself asserts, using your general knowledge. "
       "`root_question` only tells you what topic the claim is about: do not reward or penalise `claim` for which answer "
       "to `root_question` it favours, and do not assume any other claim is true or false. If `claim` is phrased as a "
       "question, judge how likely its answer is yes.")
P_BARE = ("How likely is `claim` to be true? Judge only what `claim` itself asserts, using your general knowledge, and do "
          "not assume any other claim is true or false. If `claim` is phrased as a question, judge how likely its answer is yes.")
SC = {"type": "choice", "instructions": {"question":
      "Is `claim` self-contained: could a reader who sees nothing but this one sentence tell exactly what it asserts, "
      "and judge whether it is true?"},
      "criteria": {
          "SELF_CONTAINED": "Self-contained: its subject, scope and every thing it refers to are named explicitly in the sentence.",
          "NEEDS_CONTEXT": "Needs context: it relies on something not stated in the sentence — a pronoun or 'this'/'such'/'the' "
                           "pointing at an unnamed thing (a policy, a bill, an experiment, a study), an implicit subject, "
                           "or a scope the reader would have to guess."}}


def judge(c):
    try:
        bare, _ = common.jev({"claim": c["text"]}, {"sc": SC, "p": {"type": "score", "instructions": P_BARE, "criteria": LEVELS}})
        q, _ = common.jev({"root_question": c["question"], "claim": c["text"]},
                          {"p": {"type": "score", "instructions": P_Q, "criteria": LEVELS}})
        return {"id": c["id"], "sc": bare["sc"]["choice"], "p_bare": bare["p"]["score"] / 4, "p_q": q["p"]["score"] / 4}
    except Exception as e:  # noqa: BLE001 - experiment script: record and move on
        return {"id": c["id"], "error": repr(e)}


if __name__ == "__main__":
    claims = common.load_claims()
    if sys.argv[1:] == ["rewrites"]:
        items = [{"id": g["id"], "text": g["rewrite"], "question": claims[g["id"]]["question"]}
                 for g in common.load("sc_gold.json") if g["rewrite"]]
        out = "sc_rewrites_out.json"
    else:
        items, out = claims, "sc.json"
    with ThreadPoolExecutor(6) as ex:
        res = list(ex.map(judge, items))
    common.save(res, out)
    print(len(res), "claims, errors", sum("error" in r for r in res), file=sys.stderr)
