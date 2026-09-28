#!/usr/bin/env python3
"""Jev absoluteness judgments, ISO arm (state = {root_question, claim}; only the objected answer is named).
Per item one request with 4 questions: noul|A, noul|B, score|A, score|B. PAID only with --live; cap $2."""
import sys, json, os, random
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "pilot"))
import pilot as P
from items import Q, items

OUT = HERE / "jev_responses.jsonl"

NOUL_Q = {
 "A": "Suppose `claim` is true and is offered as an objection to `candidate_answer` as the answer to `root_question`. "
      "Is it an ABSOLUTE objection: would it still count against `candidate_answer` even if no other answer to "
      "`root_question` existed? Answer no if it counts against `candidate_answer` only by favouring some other answer "
      "(another answer is better, cheaper, better supported or more popular). Judge the kind of objection, not its strength.",
 "B": "Imagine `candidate_answer` were the only answer anyone had ever proposed to `root_question`. If `claim` is true, "
      "would it still be a reason to doubt or reject `candidate_answer`? Answer no if `claim` only says, or only shows, "
      "that some other answer is better or better supported. A weak reason still counts as yes.",
}
NOUL_YES = "Absolute: `claim` counts against `candidate_answer` on its own terms, even with no rival answer."
NOUL_NO = "Comparative: `claim` counts against `candidate_answer` only by favouring another answer."

SCORE_Q = {
 "A": "Suppose `claim` is true and is offered as an objection to `candidate_answer` as the answer to `root_question`. "
      "Rate what KIND of objection it is: does it count against `candidate_answer` on its own terms, or only by "
      "favouring another answer? Rate the kind, not the strength.",
 "B": "Imagine `candidate_answer` were the only answer anyone had proposed to `root_question`. If `claim` is true, how "
      "much of its force against `candidate_answer` would remain? Rate the kind of objection, not how strong it is.",
}
SCORE_LEVELS = [
 "Purely comparative: `claim` only favours another answer; with no rival it would say nothing against `candidate_answer`.",
 "Mostly comparative: `claim` mainly favours another answer, with at most a slight point against `candidate_answer` itself.",
 "Mixed: `claim` is partly a point against `candidate_answer` itself and partly a point in favour of another answer.",
 "Mostly absolute: `claim` is mainly a point against `candidate_answer` itself, with some comparison to other answers.",
 "Absolute: `claim` counts against `candidate_answer` on its own terms (false, infeasible, ineffective or bad), regardless of any rival.",
]

def build():
    reqs = []
    for x in items():
        cand = Q[x["qid"]]["cls"][x["cls"]]
        qs = {}
        for p in ("A", "B"):
            qs[f"noul|{p}"] = P.noul({"candidate_answer": cand, "question": NOUL_Q[p]}, yes=NOUL_YES, no=NOUL_NO)
            qs[f"score|{p}"] = P.score({"candidate_answer": cand, "question": SCORE_Q[p]}, SCORE_LEVELS)
        reqs.append({"rid": x["id"], "state": {"root_question": Q[x["qid"]]["q"], "claim": x["claim"]}, "questions": qs})
    return reqs

def main():
    reqs = build()
    tok = sum(len(json.dumps({"state": r["state"], "questions": r["questions"]})) / 4 + 150 for r in reqs)
    print(f"{len(reqs)} requests, ~{tok:.0f} tokens, ~${tok * P.USD_PER_M_INPUT / 1e6:.4f}", flush=True)
    if "--live" not in sys.argv: return
    cap = 2.0; key = os.environ["TYPESAFE_API_KEY"]
    done = {json.loads(l)["rid"] for l in OUT.open()} if OUT.exists() else set()
    todo = [r for r in reqs if r["rid"] not in done]; random.Random(7).shuffle(todo)
    spent = 0; out = OUT.open("a")
    def one(r):
        for attempt in range(2):
            try: return r, P.post({"model": P.MODEL, "state": r["state"], "questions": r["questions"]}, key)
            except Exception as e: err = e
        return r, {"error": repr(err)}
    with ThreadPoolExecutor(8) as ex:
        for r, resp in ex.map(one, todo):
            if "error" in resp: print("FAIL", r["rid"], resp["error"], flush=True); continue
            spent += (resp.get("usage") or {}).get("input_tokens", 0)
            out.write(json.dumps({"rid": r["rid"], "response": resp}) + "\n"); out.flush()
            if spent * P.USD_PER_M_INPUT / 1e6 > cap: sys.exit("cap reached")
    print(f"done: {spent} input tokens ~${spent * P.USD_PER_M_INPUT / 1e6:.4f}")

if __name__ == "__main__": main()
