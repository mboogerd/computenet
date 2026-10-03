"""Re-render agreement, confusion matrices and per-outcome precision/recall."""

from __future__ import annotations

from collections import Counter
from math import comb
import random

import common


LABELS = ("DISPUTES_CLAIM", "DENIES_BEARING", "NEITHER")


def metrics(gold: dict[int, str], predicted: dict[int, str], name: str) -> list[str]:
    lines = [name, "gold\\pred " + " ".join(f"{label:>15}" for label in LABELS)]
    for actual in LABELS:
        cells = [sum(gold[i] == actual and predicted[i] == guess for i in gold) for guess in LABELS]
        lines.append(f"{actual:15} " + " ".join(f"{cell:15d}" for cell in cells))
    correct = sum(gold[i] == predicted[i] for i in gold)
    f1s = []
    lines.append("outcome          support       precision          recall              f1")
    for label in LABELS:
        tp = sum(gold[i] == predicted[i] == label for i in gold)
        fp = sum(gold[i] != label and predicted[i] == label for i in gold)
        fn = sum(gold[i] == label and predicted[i] != label for i in gold)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        f1s.append(f1)
        lines.append(f"{label:15} {tp + fn:7d} {precision:15.3f} {recall:15.3f} {f1:15.3f}")
    lines.append(f"accuracy {correct}/{len(gold)} = {correct / len(gold):.3f}; macro-F1 {sum(f1s) / len(f1s):.3f}")
    return lines


def main() -> None:
    gold_doc = common.load("gold.json")
    gold = {row["id"]: row["label"] for row in gold_doc["items"]}
    jev = common.load("jev.json")
    choice = {row["id"]: row["choice"] for row in jev["items"]}
    graded = {row["id"]: row["graded"] for row in jev["items"]}
    if set(gold) != set(choice) or set(gold) != set(graded):
        raise AssertionError("gold and prediction ids differ")
    votes = {name: {row["id"]: row["label"] for row in common.load(f"rater-{name}.json")["labels"]}
             for name in ("opus", "sonnet", "opus-adjudicator")}
    primary = sum(votes["opus"][i] == votes["sonnet"][i] for i in gold)
    observed = primary / len(gold)
    op = Counter(votes["opus"].values())
    so = Counter(votes["sonnet"].values())
    expected = sum(op[label] * so[label] for label in LABELS) / len(gold) ** 2
    kappa = (observed - expected) / (1 - expected) if expected < 1 else 1.0
    lines = [
        f"items {len(gold)}; gold support {dict(Counter(gold.values()))}",
        f"Opus/Sonnet exact agreement {primary}/{len(gold)} = {observed:.3f}; Cohen kappa {kappa:.3f}; "
        f"three-way splits {gold_doc['all_different']}",
        "",
        *metrics(gold, choice, "CURRENT CHOICE"),
        "",
        *metrics(gold, graded, "GRADED TWO-SCORE"),
        "",
    ]
    choice_only = sum(choice[i] == gold[i] and graded[i] != gold[i] for i in gold)
    graded_only = sum(graded[i] == gold[i] and choice[i] != gold[i] for i in gold)
    lines.append(f"paired disagreements: Choice-only correct {choice_only}; graded-only correct {graded_only}")
    discordant = choice_only + graded_only
    tail = min(choice_only, graded_only)
    mcnemar_p = min(1.0, 2 * sum(comb(discordant, k) for k in range(tail + 1)) / 2**discordant)
    ids = sorted(gold)
    rng = random.Random(20261004)
    deltas = []
    for _ in range(20_000):
        sample = [rng.choice(ids) for _ in ids]
        deltas.append(
            sum(graded[i] == gold[i] for i in sample) / len(sample)
            - sum(choice[i] == gold[i] for i in sample) / len(sample)
        )
    deltas.sort()
    accuracy_delta = (
        sum(graded[i] == gold[i] for i in ids) - sum(choice[i] == gold[i] for i in ids)
    ) / len(ids)
    lines.append(
        f"graded - Choice accuracy {accuracy_delta:.3f}; paired bootstrap 95% CI "
        f"[{deltas[499]:.3f}, {deltas[19_499]:.3f}]; exact McNemar p={mcnemar_p:.3f}"
    )
    output = "\n".join(lines) + "\n"
    (common.DATA / "metrics.txt").write_text(output, encoding="utf-8")
    print(output, end="")


if __name__ == "__main__":
    main()
