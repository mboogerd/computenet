"""Shared I/O and TypeSafe System One client for the model-B measurement."""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path


HERE = Path(__file__).resolve().parent
DATA = HERE / "data"


def load(name: str):
    with (DATA / name).open(encoding="utf-8") as handle:
        return json.load(handle)


def save(value, name: str) -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    with (DATA / name).open("w", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def jev(state: dict, questions: dict, attempts: int = 6) -> dict:
    """Make one Jev request, retaining the complete response for audit."""
    body = json.dumps({"model": "jev-latest", "state": state, "questions": questions}).encode()
    headers = {
        "Authorization": f"Bearer {os.environ['TYPESAFE_API_KEY']}",
        "Content-Type": "application/json",
    }
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(
                "https://api.typesafe.ai/v1/systemone", data=body, headers=headers
            )
            with urllib.request.urlopen(request, timeout=90) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code not in (429, 529):
                raise
            time.sleep(0.5 * 2**attempt)
        except (TimeoutError, OSError):
            time.sleep(1 + attempt)
    raise RuntimeError("Jev retries exhausted")
