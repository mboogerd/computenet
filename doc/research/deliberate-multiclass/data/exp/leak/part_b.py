"""Part B: new ISO prompt shapes + placebo baseline (PAID with --live). Reuses pilot.py request machinery."""
import sys, json, os, random
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "pilot"))
import pilot as P

OUT = HERE / "responses_b.jsonl"
PLACEBOS = {"honey": "Honey is made by bees from flower nectar.",
            "violin": "A standard violin has four strings."}

DELTA_LEVELS = [
    "Ruled out: learning `claim` makes `candidate_answer` impossible, except under exceptional conditions.",
    "Less likely: learning `claim` makes `candidate_answer` less likely than it was before, without ruling it out.",
    "Unchanged: learning `claim` leaves the chance that `candidate_answer` is correct where it was before.",
    "More likely: learning `claim` makes `candidate_answer` more likely than it was before, without settling it.",
    "Settled: learning `claim` makes `candidate_answer` certain, except under exceptional conditions.",
]
def delta_q(cand, p, extra=None):
    q = {"A": "Before learning `claim`, you had some view of whether `candidate_answer` is the correct answer to "
              "`root_question`. Now you learn that `claim` is true. Does learning it make `candidate_answer` more "
              "likely, less likely, or leave it unchanged? Rate only the change, not where `candidate_answer` ends up.",
         "B": "Whatever your current estimate that `candidate_answer` is the correct answer to `root_question`, how "
              "should that estimate move when you learn that `claim` is true? Rate the direction and size of the "
              "update, not the estimate itself."}[p]
    return P.score({"candidate_answer": cand, **(extra or {}), "question": q}, DELTA_LEVELS)

def pin_q(cand, p, extra=None):  # (2) abstracted: the candidate's own plausibility pinned to 50%
    q = {"A": "Treat `candidate_answer` as a hypothesis about `root_question` that you currently hold at exactly 50%, "
              "whatever you actually think of it. Suppose `claim` is true. How does that bear on this hypothesis?",
         "B": "Pretend you have no opinion at all on whether `candidate_answer` is the answer to `root_question`: it "
              "is a coin flip to you. If `claim` is true, is `candidate_answer` then a better or a worse answer than "
              "that coin flip?"}[p]
    return P.score({"candidate_answer": cand, **(extra or {}), "question": q}, P.BEAR_LEVELS)

NOT_PLAUS = ("Do not judge whether `candidate_answer` is plausible, well supported or likely overall. An answer you "
             "consider unlikely must still get 'No bearing' when `claim` has nothing to do with it, and an answer you "
             "consider likely must not get 'Counts for it' merely because it is likely.")
STRICT_RO = (" Reserve 'Rules it out' for a strict contradiction: `claim` and `candidate_answer` cannot both be true. "
             "If `claim` only makes `candidate_answer` less likely, even much less likely, choose 'Counts against it'.")
def instr_q(cand, p, extra=None, strict=False):  # (4)
    q = {"A": "Suppose `claim` is true. How does that bear on `candidate_answer` as the answer to `root_question`? ",
         "B": "If `claim` is true, is `candidate_answer` a better or a worse answer to `root_question` than it would otherwise be? "}[p]
    return P.score({"candidate_answer": cand, **(extra or {}), "question": q + NOT_PLAUS + (STRICT_RO if strict else "")},
                   P.BEAR_LEVELS)
def instrx_q(cand, p, extra=None): return instr_q(cand, p, extra, strict=True)

def cinstr_q(cand, p, extra=None):  # compat Noul with anti-plausibility instruction
    q = {"A": "Suppose `claim` is true. Could `candidate_answer` still be the correct answer to `root_question`? "
              "Answer no only if `claim` and `candidate_answer` cannot both be true. Do not judge whether "
              "`candidate_answer` is plausible or likely: an unlikely answer that `claim` does not contradict is still possible.",
         "B": "Can `claim` and `candidate_answer` both be true together, that is, `claim` true and `candidate_answer` "
              "the correct answer to `root_question`? Answer no only if they contradict each other. How likely "
              "`candidate_answer` is overall is irrelevant here."}[p]
    return P.noul({"candidate_answer": cand, **(extra or {}), "question": q},
                  yes="Still possible: `claim` and `candidate_answer` can both be true.",
                  no="Ruled out: `claim` and `candidate_answer` cannot both be true.")

NEW = {"delta": delta_q, "pin": pin_q, "instr": instr_q, "instrx": instrx_q, "cinstr": cinstr_q}

def build():
    ts = json.loads(P.TESTSET.read_text()); reqs = []
    for q in ts["questions"]:
        classes = list(q["classes"].items())
        for p in P.PARAPHRASES:
            for c in q["claims"]:
                st = {"root_question": q["root_question"], "claim": c["text"]}
                qs = {f"{s}|{cid}": fn(t, p, P.none_extra(q, cid)) for s, fn in NEW.items() for cid, t in classes}
                reqs.append({"rid": f"B|{c['id']}|{p}", "state": st, "questions": qs})
            for pid, ptxt in PLACEBOS.items():  # placebo baseline: old + new shapes, same wording
                st = {"root_question": q["root_question"], "claim": ptxt}
                shapes = {**P.PER_CLASS, **NEW}
                qs = {f"{s}|{cid}": fn(t, p, P.none_extra(q, cid)) for s, fn in shapes.items() for cid, t in classes}
                reqs.append({"rid": f"PLC|{q['id']}/{pid}|{p}", "state": st, "questions": qs})
    return reqs

def main():
    reqs = build()
    tok = sum(len(json.dumps({"state": r["state"], "questions": r["questions"]})) / 4 + 150 for r in reqs)
    print(f"{len(reqs)} requests, {sum(len(r['questions']) for r in reqs)} questions, ~{tok:.0f} tokens, ~${tok * P.USD_PER_M_INPUT / 1e6:.4f}")
    if "--live" not in sys.argv: return
    cap = 2.0
    key = os.environ["TYPESAFE_API_KEY"]
    done = {json.loads(l)["rid"] for l in OUT.open()} if OUT.exists() else set()
    todo = [r for r in reqs if r["rid"] not in done]; random.Random(7).shuffle(todo)
    spent = 0; out = OUT.open("a")
    def one(r): return r, P.post({"model": P.MODEL, "state": r["state"], "questions": r["questions"]}, key)
    with ThreadPoolExecutor(8) as ex:
        for r, resp in ex.map(one, todo):
            spent += (resp.get("usage") or {}).get("input_tokens", 0)
            out.write(json.dumps({"rid": r["rid"], "response": resp}) + "\n"); out.flush()
            if spent * P.USD_PER_M_INPUT / 1e6 > cap: sys.exit("cap reached")
    print(f"done: {spent} input tokens ~${spent * P.USD_PER_M_INPUT / 1e6:.4f}")

if __name__ == "__main__": main()
