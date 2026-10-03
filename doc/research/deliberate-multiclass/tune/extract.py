#!/usr/bin/env python3
"""Flatten the cached one-level judgements into one tuning dataset (dataset.json, git-ignored). Offline; no model calls.
Reads ../data/exp (copy the research data there first; see ../data/DATA.md).

Deliberately EXCLUDED as inputs: Opus's and Sol's direct answer distributions. Claims proposed by Opus/Sol stay in:
they are the deliberation's arguments, not its verdict.

Per question: id, set, split, K, classes, y (answer index), and
  pos[c]      Jev plausibility of 'the answer is c' (the model-A position prior), pos_out[c] its outside-knowledge flag
  claims[j]   src; p (plausibility with the question); pcf (context-free, push only, else = p); outside; pconf;
              bA[c], bB[c] (bearing on c as score/4, paraphrases A/B); bconf; r0[c] / r4[c] (mean P(rules out) / P(settles))
Sets: push (80 SuperGPQA-hard, tune 55 / heldout 25) and know (50 knowledge questions, all used for training).
Forecasting (e2e) is left out: it used the older BEAR shape and Jev was outside its knowledge on most of it."""
import json
from pathlib import Path
EXP = Path(__file__).resolve().parent.parent / "data" / "exp"

def load(p):
    return {(r := json.loads(l))["key"]: r for l in p.open()} if p.exists() else {}

def plaus(a):
    out = a["knowledge"]["choice"] == "OUTSIDE_MY_KNOWLEDGE"
    return (0.5 if out else min(max(a["plausibility"]["score"] / 4, 0), 1)), out, a["plausibility"].get("confidence", 0.5)

def build(qs, setname, llm, jev, srcs, split_of):
    ans = lambda k: jev[k]["response"]["answers"] if k in jev else None
    out = []
    for q in qs:
        cs = list(q["classes"])
        pos = [ans(f"POS|{q['id']}|{c}") for c in cs]
        rec = {"id": q["id"], "set": setname, "split": split_of(q), "K": len(cs), "classes": cs, "y": cs.index(q["answer"]),
               "pos": [plaus(a)[0] if a else 0.5 for a in pos], "pos_out": [bool(a and plaus(a)[1]) for a in pos], "claims": []}
        for src in srcs:
            r = llm.get(f"{src}|propose|{q['id']}|0")
            for i, _ in enumerate(r["parsed"][:16] if r else []):
                pa = ans(f"PL|{src}|{q['id']}|{i}"); pcf = ans(f"PLCF|{src}|{q['id']}|{i}")
                bs = [ans(f"BEARI|{src}|{q['id']}|{i}|{p}") for p in "AB"]
                if not pa or not all(bs): continue
                p, outside, pconf = plaus(pa)
                b = [[bb[f"bear|{c}"] for c in cs] for bb in bs]
                rec["claims"].append({
                    "src": src, "p": p, "pcf": plaus(pcf)[0] if pcf else p, "outside": outside, "pconf": pconf,
                    "bA": [x["score"] / 4 for x in b[0]], "bB": [x["score"] / 4 for x in b[1]],
                    "bconf": sum(x.get("confidence", 0.5) for bb in b for x in bb) / (2 * len(cs)),
                    "r0": [(b[0][k]["probabilities"]["0"] + b[1][k]["probabilities"]["0"]) / 2 for k in range(len(cs))],
                    "r4": [(b[0][k]["probabilities"]["4"] + b[1][k]["probabilities"]["4"]) / 2 for k in range(len(cs))]})
        out.append(rec)
    return out

if __name__ == "__main__":
    push, e2e2 = EXP / "push", EXP / "e2e2"
    D = build(json.loads((push / "questions.json").read_text())["questions"], "push", load(push / "cache/llm.jsonl"),
              load(push / "cache/jev.jsonl"), ("opus", "sol"), lambda q: q["split"])
    D += build(json.loads((e2e2 / "questions_knowledge.json").read_text())["questions"], "know",
               {**load(EXP / "e2e/cache/llm.jsonl"), **load(e2e2 / "cache/llm.jsonl")},
               {**load(EXP / "e2e/cache/jev.jsonl"), **load(e2e2 / "cache/jev.jsonl")}, ("opus",), lambda q: "tune")
    (Path(__file__).parent / "dataset.json").write_text(json.dumps(D))
    for s in ("push", "know"):
        x = [d for d in D if d["set"] == s]
        print(s, len(x), "questions;", sum(len(d["claims"]) for d in x), "claims; K",
              min(d["K"] for d in x), "-", max(d["K"] for d in x), "; no-claim questions", sum(not d["claims"] for d in x))
