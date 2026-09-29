"""Shared loading and Jev transport for the claim-reuse experiments (computenet-6uimj).

Every script runs from anywhere: data lives in ./data next to this file.
"""
import json, os, time, urllib.error, urllib.request

DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
RUNS = ("runA", "runB")


def path(name):
    return os.path.join(DATA, name)


def load(name):
    with open(path(name)) as f:
        return json.load(f)


def save(obj, name):
    with open(path(name), "w") as f:
        json.dump(obj, f, separators=(",", ":"), ensure_ascii=False)


def graph(run):
    return load(f"{run}.json")


def load_claims():
    """Every non-root claim of both snapshots, in a stable order; `id` is the index."""
    claims = []
    for run in RUNS:
        g = graph(run)
        qtext = {q["root"]: q["text"] for q in g["questions"]}
        nodes = {n["ref"]: n for n in g["nodes"]}
        parent = {n["source"]: (n["target"], n["polarity"], n.get("strength")) for n in g["nodes"] if n["kind"] == "EDGE"}
        for n in g["nodes"]:
            if n["kind"] != "CLAIM" or n["ref"] in qtext or "text" not in n:
                continue
            t, pol, s = parent.get(n["ref"], (None, None, None))
            tgt = nodes.get(t, {})
            ptext = tgt.get("text") if tgt.get("kind") == "CLAIM" else f"[link] {nodes.get(tgt.get('source'), {}).get('text', '?')}"
            claims.append(dict(id=len(claims), run=run, q=f"{run}:{qtext[n['root']]}", question=qtext[n["root"]],
                               text=n["text"], parent=ptext, polarity=pol, strength=s,
                               plausibility=n.get("plausibility"), depth=n.get("depth")))
    return claims


def expand(rows, claims):
    """[[pair, a, b, cos], ...] -> pair dicts carrying both claims' text, question, parent and polarity."""
    side = lambda c: dict(question=c["question"], parent=c["parent"], polarity=c["polarity"], claim=c["text"])
    return [dict(pair=p, a=a, b=b, cos=cos, A=side(claims[a]), B=side(claims[b])) for p, a, b, cos in rows]


def jev(state, questions, attempts=6):
    """One TypeSafe System One request (as JevJudge.evaluate); retries 429/529 and timeouts."""
    body = json.dumps({"model": "jev-latest", "state": state, "questions": questions}).encode()
    headers = {"Authorization": f"Bearer {os.environ['TYPESAFE_API_KEY']}", "Content-Type": "application/json"}
    for a in range(attempts):
        try:
            req = urllib.request.Request("https://api.typesafe.ai/v1/systemone", data=body, headers=headers)
            with urllib.request.urlopen(req, timeout=90) as r:
                j = json.load(r)
                return j["answers"], j.get("usage")
        except urllib.error.HTTPError as e:
            if e.code not in (429, 529):
                raise
            time.sleep(0.5 * 2 ** a)
        except (TimeoutError, OSError):
            time.sleep(1 + a)
    raise RuntimeError("Jev retries exhausted")
