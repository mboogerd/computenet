"""Print the blind gold-labelling prompt consumed independently by each rater."""

from __future__ import annotations

import json

import common


RUBRIC = """Assign exactly one routing label to each item.

DISPUTES_CLAIM: candidate_argument gives a reason that claim itself is false or
overstated as worded. Its force remains even when you ignore why claim was
offered in the parent debate.

DENIES_BEARING: grant claim as true, then candidate_argument gives a reason the
claim nevertheless does not support or attack parent_claim in claim_direction.
It attacks the connection, not the premise.

NEITHER: candidate_argument does neither. This includes an independent reason
about parent_claim, a countervailing cost or benefit, off-topic or incoherent
text, a question, or a restatement. An important point about the broader debate
is still NEITHER unless it disputes claim or the specified connection.

Use the natural reading of each sentence. Judge only these definitions; do not
guess what another judge would answer. You are not shown any Jev output or any
other rater's answer. Return all items, in id order, as JSON matching the given
schema. Keep each reason to one short sentence."""


def main() -> None:
    corpus = common.load("corpus.json")
    items = []
    for item in corpus["items"]:
        state = item["state"]
        items.append(
            {
                "id": item["id"],
                "root_question": state["root_question"],
                "path_from_root": state["path_from_root"],
                "parent_claim": state["parent_claim"],
                "claim": state["claim"],
                "claim_direction": state["claim_direction"],
                "candidate_argument": item["candidate_argument"],
            }
        )
    print(RUBRIC)
    print("\nITEMS")
    print(json.dumps(items, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    main()
