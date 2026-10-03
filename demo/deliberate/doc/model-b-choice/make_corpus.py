"""Extract every non-root con from the committed deliberate calibration material.

The resulting 64 items preserve the exact state fields used by JevJudge.bearing.
No label or judge output participates in selection.
"""

from __future__ import annotations

import json
from pathlib import Path

import common


MATERIAL = common.HERE.parent.parent / "src/test/resources/calibration/material.json"


def side_of(previous: dict, claim: str) -> str:
    in_pro = any(argument["text"] == claim for argument in previous["pros"])
    in_con = any(argument["text"] == claim for argument in previous["cons"])
    if in_pro == in_con:
        raise ValueError(f"chain claim is not on exactly one prior side: {claim!r}")
    return "SUPPORT" if in_pro else "ATTACK"


def main() -> None:
    material = json.loads(MATERIAL.read_text(encoding="utf-8"))
    contexts = []
    items = []
    for question_index, run in enumerate(material):
        for depth in range(1, len(run["chain"])):
            node = run["chain"][depth]
            side = side_of(run["chain"][depth - 1], node["claim"])
            context_id = f"q{question_index}-d{depth}"
            state = {
                "root_question": run["question"],
                "path_from_root": node["path"],
                "parent_claim": node["path"][-1],
                "claim": node["claim"],
                "claim_direction": "supports" if side == "SUPPORT" else "attacks",
            }
            ids = []
            for candidate_index, argument in enumerate(node["cons"]):
                item_id = len(items)
                ids.append(item_id)
                items.append(
                    {
                        "id": item_id,
                        "context_id": context_id,
                        "candidate_index": candidate_index,
                        "candidate_proposer": argument["proposer"],
                        "state": state,
                        "candidate_argument": argument["text"],
                    }
                )
            contexts.append({"id": context_id, "state": state, "item_ids": ids})
    corpus = {
        "source": "demo/deliberate/src/test/resources/calibration/material.json",
        "selection": "all con arguments at non-root chain nodes, before any measurement labels or outputs",
        "contexts": contexts,
        "items": items,
    }
    if len(items) < 60:
        raise AssertionError(f"acceptance requires at least 60 items, found {len(items)}")
    common.save(corpus, "corpus.json")
    print(f"wrote {len(items)} items in {len(contexts)} contexts")


if __name__ == "__main__":
    main()
