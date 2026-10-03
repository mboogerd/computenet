"""Validate a CLI rater response and copy only its structured labels into data/."""

from __future__ import annotations

import json
import sys
from pathlib import Path

import common


def main() -> None:
    source, name = Path(sys.argv[1]), sys.argv[2]
    envelope = json.loads(source.read_text(encoding="utf-8"))
    document = envelope
    if "structured_output" in envelope:
        document = envelope["structured_output"]
    elif isinstance(envelope.get("result"), str):
        document = json.loads(envelope["result"])
    rows = document.get("labels")
    if not isinstance(rows, list) or len(rows) != 64:
        raise AssertionError(f"{name}: expected 64 labels")
    ids = [row.get("id") for row in rows]
    if ids != list(range(64)):
        raise AssertionError(f"{name}: labels must be in id order")
    metadata = {
        key: envelope[key]
        for key in ("modelUsage", "total_cost_usd", "usage", "duration_api_ms")
        if key in envelope
    }
    common.save({"rater": name, "metadata": metadata, "labels": rows}, f"rater-{name}.json")
    print(f"captured {len(rows)} {name} labels")


if __name__ == "__main__":
    main()
