"""Resolve blind ratings into gold without consulting Jev.

Opus and Sonnet are the primary raters. Their agreement is accepted. On a
disagreement, a fresh blind Opus pass is the adjudicator. The adjudicator sees
neither primary answer. The votes and reason for resolution remain in
gold.json.
"""

from __future__ import annotations

import common


LABELS = {"DISPUTES_CLAIM", "DENIES_BEARING", "NEITHER"}
RATERS = ("opus", "sonnet", "opus-adjudicator")


def load_rater(name: str, expected: set[int]) -> dict[int, dict]:
    rows = common.load(f"rater-{name}.json")["labels"]
    by_id = {row["id"]: row for row in rows}
    if set(by_id) != expected or len(rows) != len(by_id):
        raise AssertionError(f"{name}: ids must be unique and exactly {sorted(expected)}")
    if any(row["label"] not in LABELS for row in rows):
        raise AssertionError(f"{name}: unknown label")
    return by_id


def main() -> None:
    ids = {item["id"] for item in common.load("corpus.json")["items"]}
    votes = {name: load_rater(name, ids) for name in RATERS}
    gold = []
    primary_agreement = 0
    all_different = 0
    for item_id in sorted(ids):
        labels = {name: votes[name][item_id]["label"] for name in RATERS}
        if labels["opus"] == labels["sonnet"]:
            label = labels["opus"]
            resolution = "opus-sonnet agreement"
            primary_agreement += 1
        else:
            label = labels["opus-adjudicator"]
            resolution = "fresh blind opus adjudication"
            if len(set(labels.values())) == 3:
                all_different += 1
        gold.append(
            {
                "id": item_id,
                "label": label,
                "resolution": resolution,
                "votes": labels,
                "reasons": {name: votes[name][item_id]["reason"] for name in RATERS},
            }
        )
    common.save(
        {
            "policy": "Opus+Sonnet agreement; a fresh blind Opus pass adjudicates disagreement",
            "primary_agreement": primary_agreement,
            "all_different": all_different,
            "items": gold,
        },
        "gold.json",
    )
    print(f"gold {len(gold)}; Opus/Sonnet agree {primary_agreement}; all-different {all_different}")


if __name__ == "__main__":
    main()
