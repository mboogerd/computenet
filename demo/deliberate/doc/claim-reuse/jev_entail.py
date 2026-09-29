"""Precision stage, variant 2: two directional implication Choices per pair (bare state).

`ab`: does claim_a being true guarantee claim_b?  `ba`: the converse. Mutual = reuse; one-way = implication edge.
Writes data/<out>: {pair: {"ab", "ba", "usage"}}.
Run: TYPESAFE_API_KEY=... python3 jev_entail.py sample.json jev_entail.json 0.85
     TYPESAFE_API_KEY=... python3 jev_entail.py all80.json jev_entail_all80.json 0.80
"""
import sys
from concurrent.futures import ThreadPoolExecutor
import common


def implies(x, y):
    return {"type": "choice", "instructions": {"question":
        f"If `{x}` is true, must `{y}` also be true, taking each exactly as worded — including every qualifier, "
        f"number, date, scope and example it names? Answer NO if `{y}` asserts anything `{x}` does not."},
        "criteria": {"YES": f"Yes: `{x}` being true guarantees `{y}` is true; `{y}` adds nothing beyond `{x}`.",
                     "NO": f"No: `{y}` asserts something `{x}` does not establish (a detail, qualifier, broader or different claim)."}}


def ask(p):
    try:
        answers, usage = common.jev({"claim_a": p["A"]["claim"], "claim_b": p["B"]["claim"]},
                                    {"ab": implies("claim_a", "claim_b"), "ba": implies("claim_b", "claim_a")})
        return {"ab": answers["ab"]["choice"], "ba": answers["ba"]["choice"], "usage": usage}
    except Exception as e:  # noqa: BLE001 - experiment script: record and move on
        return {"error": repr(e)}


if __name__ == "__main__":
    src, out, min_cos = sys.argv[1], sys.argv[2], float(sys.argv[3])
    pairs = [p for p in common.expand(common.load(src), common.load_claims()) if p["cos"] >= min_cos]
    with ThreadPoolExecutor(6) as ex:
        res = list(ex.map(ask, pairs))
    common.save({p["pair"]: r for p, r in zip(pairs, res)}, out)
    print(len(res), "pairs, errors", sum("error" in r for r in res), file=sys.stderr)
