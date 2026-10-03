"""Run the shipped Choice and a predeclared two-Score alternative on the corpus.

Requests are batched by claim context, matching JevJudge.bearing's production
shape. Choice and graded questions use separate calls so neither can influence
the other's response. Complete API responses are retained in data/jev.json.
"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

import common


CHOICE_CRITERIA = {
    "DISPUTES_CLAIM": "Disputes the claim: it gives a reason to think `claim` itself is false or overstated.",
    "DENIES_BEARING": (
        "Denies the bearing: even granting that `claim` is true, it argues that `claim` does not show "
        "what it is offered to show about `parent_claim` — the connection fails, not the claim."
    ),
    "NEITHER": (
        "Neither: it is not a real argument about `claim` or about its bearing on `parent_claim` "
        "(off-topic, incoherent, a question, or a restatement)."
    ),
}

PREMISE_LEVELS = [
    "Not at all: it leaves whether `claim` is true entirely untouched.",
    "Weakly: it is at most an indirect or minor reason to qualify `claim`.",
    "Materially: it gives a real but ambiguous or limited reason to doubt `claim` as worded.",
    "Strongly: it directly shows that `claim` is probably false or materially overstated.",
    "Decisively: if the candidate is right, `claim` as worded cannot be true.",
]

BEARING_LEVELS = [
    "Not at all: granting `claim`, it leaves the specified connection to `parent_claim` untouched.",
    "Weakly: granting `claim`, it raises at most a minor or indirect concern about the connection.",
    "Materially: granting `claim`, it gives a real but ambiguous or limited reason the connection fails.",
    "Strongly: granting `claim`, it directly shows the claim bears little on `parent_claim` in that direction.",
    "Decisively: granting `claim`, the specified support or attack on `parent_claim` cannot hold.",
]


def choice_question(item: dict) -> dict:
    state = item["state"]
    return {
        "type": "choice",
        "instructions": {
            "candidate_argument": item["candidate_argument"],
            "question": (
                "`candidate_argument` was proposed as an argument against `claim`, which is offered as a reason "
                f"{'for' if state['claim_direction'] == 'supports' else 'against'} `parent_claim` "
                "(`claim_direction`). Does it dispute whether `claim` is true, or does it grant `claim` and deny "
                "only that it bears on `parent_claim`?"
            ),
        },
        "criteria": CHOICE_CRITERIA,
    }


def score_question(item: dict, kind: str) -> dict:
    if kind == "premise":
        question = (
            "How strongly does `candidate_argument` give a reason to doubt that `claim` itself is true as worded? "
            "Judge only the premise's truth; ignore whether it is a good reason for `parent_claim`."
        )
        levels = PREMISE_LEVELS
    else:
        question = (
            "Assume `claim` is true. How strongly does `candidate_argument` show that `claim` fails to "
            "`claim_direction` `parent_claim`? Ignore reasons the claim itself may be false and independent reasons "
            "for or against `parent_claim`."
        )
        levels = BEARING_LEVELS
    return {
        "type": "score",
        "instructions": {"candidate_argument": item["candidate_argument"], "question": question},
        "criteria": levels,
    }


def classify(premise: int, bearing: int) -> str:
    """Predeclared mapping: level 2 is material; an exact tie stays on ADD."""
    if max(premise, bearing) < 2:
        return "NEITHER"
    return "DISPUTES_CLAIM" if premise >= bearing else "DENIES_BEARING"


def run_context(context: dict, by_id: dict[int, dict]) -> list[dict]:
    items = [by_id[item_id] for item_id in context["item_ids"]]
    choice_questions = {f"c{item['id']}": choice_question(item) for item in items}
    graded_questions = {
        key: question
        for item in items
        for key, question in (
            (f"p{item['id']}", score_question(item, "premise")),
            (f"b{item['id']}", score_question(item, "bearing")),
        )
    }
    choice = common.jev(context["state"], choice_questions)
    graded = common.jev(context["state"], graded_questions)
    return [
        {
            "context_id": context["id"],
            "item_ids": context["item_ids"],
            "choice_response": choice,
            "graded_response": graded,
        }
    ]


def main() -> None:
    corpus = common.load("corpus.json")
    by_id = {item["id"]: item for item in corpus["items"]}
    with ThreadPoolExecutor(max_workers=3) as executor:
        nested = list(executor.map(lambda context: run_context(context, by_id), corpus["contexts"]))
    batches = [batch for group in nested for batch in group]
    rows = []
    for batch in batches:
        choice_answers = batch["choice_response"]["answers"]
        graded_answers = batch["graded_response"]["answers"]
        for item_id in batch["item_ids"]:
            premise = graded_answers[f"p{item_id}"]["score"]
            bearing = graded_answers[f"b{item_id}"]["score"]
            rows.append(
                {
                    "id": item_id,
                    "choice": choice_answers[f"c{item_id}"]["choice"],
                    "premise_score": premise,
                    "bearing_score": bearing,
                    "graded": classify(premise, bearing),
                }
            )
    rows.sort(key=lambda row: row["id"])
    common.save(
        {
            "mapping": "NEITHER if both scores <2/4; else the higher score; ties route to DISPUTES_CLAIM",
            "items": rows,
            "batches": batches,
        },
        "jev.json",
    )
    print(f"wrote {len(rows)} item results from {len(batches) * 2} bounded Jev requests")


if __name__ == "__main__":
    main()
