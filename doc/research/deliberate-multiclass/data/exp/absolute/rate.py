#!/usr/bin/env python3
"""Blind gold rating: Opus 5.5 and Sol, item order forward and reversed, per question. 6 q x 2 orders x 2 raters = 24 calls.
Pattern copied from verify/gold/rate.py; timeouts 240s (Opus) / 300s (Sol), one retry."""
import glob, json, os, re, subprocess, sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from items import Q, items

HERE = Path(__file__).resolve().parent
RAW = HERE / "raw"; RAT = HERE / "ratings"
LABELS = {"ABSOLUTE", "COMPARATIVE", "MIXED"}

def claude_bin():
    found = glob.glob(os.path.expanduser(
        "~/Library/Application Support/Claude/claude-code/*/claude.app/Contents/MacOS/claude"))
    key = lambda p: [int(x) for x in re.findall(r"/claude-code/([\d.]+)/", p)[0].split(".")]
    return max(found, key=key) if found else "claude"

def prompt(qid, its):
    q = Q[qid]
    cls = "\n".join(f"- {c}: {t}" for c, t in q["cls"].items())
    lst = "\n".join(f"- {x['id']}: objection to {x['cls']}: \"{x['claim']}\"" for x in its)
    return f"""You are an expert rater building a gold-standard reference. Work alone, from your own knowledge.

Question: {q['q']}
{q['frame']}

Listed answers:
{cls}

Each item below is a claim offered as an objection to ONE listed answer. Assume the claim is TRUE. Classify the KIND of objection:
  ABSOLUTE     it counts against that answer on its own terms: it shows the answer false, infeasible, ineffective or bad.
               It would still count against the answer even if no other answer existed (e.g. if this were the only
               answer anyone had proposed). This is about kind, not strength: a weak objection can be ABSOLUTE.
  COMPARATIVE  it counts against that answer ONLY by favouring a rival (a rival is better, cheaper, better supported,
               more popular, or has evidence for it). If no rival answer existed it would say nothing against this answer.
  MIXED        it genuinely has both parts, or reasonable experts would split between the two.
Also give "abs": your probability (0-100) that an expert panel would call it ABSOLUTE rather than COMPARATIVE.

Items:
{lst}

Reply with ONLY one JSON object, no prose, no code fence:
{{"<item id>": {{"label": "ABSOLUTE|COMPARATIVE|MIXED", "abs": <0-100>}}, ...}}
Include all {len(its)} items.
"""

def call(member, text, tag):
    (RAW / f"{tag}.prompt.md").write_text(text)
    if member == "opus":
        r = subprocess.run([claude_bin(), "-p", "--bare", "--model", "claude-opus-5-5", "--tools", "",
                            "--no-session-persistence", "--output-format", "json"],
                           input=text, capture_output=True, text=True, timeout=240, cwd=str(RAW))
        j = json.loads(r.stdout)
        if j.get("is_error"): raise RuntimeError(f"opus {tag}: {j.get('result')}")
        out = j["result"]
        (RAW / f"{tag}.meta.json").write_text(json.dumps({k: v for k, v in j.items() if k != "result"}))
    else:
        last = RAW / f"{tag}.sol-last.txt"
        if last.exists(): last.unlink()
        r = subprocess.run(["codex", "exec", "--skip-git-repo-check", "--ephemeral", "-s", "read-only",
                            "-m", "gpt-5.6-sol", "-o", str(last), "-"],
                           input=text, capture_output=True, text=True, timeout=300, cwd=str(RAW))
        if r.returncode != 0 or not last.exists(): raise RuntimeError(f"sol {tag}: exit {r.returncode}: {r.stderr[-600:]}")
        out = last.read_text()
        (RAW / f"{tag}.sol-stderr.txt").write_text(r.stderr[-4000:])
    (RAW / f"{tag}.reply.md").write_text(out)
    return out

def parse(text, its):
    s = text[text.index("{"): text.rindex("}") + 1]
    j = json.loads(s)
    for x in its:
        v = j[x["id"]]
        if v["label"] not in LABELS or not 0 <= float(v["abs"]) <= 100: raise ValueError(f"bad {x['id']}: {v}")
    return j

def job(args):
    member, qid, rev = args
    its = [x for x in items() if x["qid"] == qid]
    if rev: its = its[::-1]
    tag = f"{member}-{qid}-{'rev' if rev else 'fwd'}"
    dest = RAT / f"{tag}.json"
    if dest.exists(): return tag, "cached"
    err = None
    for attempt in range(2):
        try:
            j = parse(call(member, prompt(qid, its), tag + (f".try{attempt}" if attempt else "")), its)
            dest.write_text(json.dumps(j, indent=1)); return tag, "ok"
        except Exception as e:
            err = e
    return tag, f"FAILED: {err!r}"

if __name__ == "__main__":
    RAW.mkdir(exist_ok=True); RAT.mkdir(exist_ok=True)
    if sys.argv[1:] == ["--print-one"]: print(prompt("Q2", [x for x in items() if x["qid"] == "Q2"])); sys.exit()
    jobs = [(m, qid, rev) for m in ("opus", "sol") for qid in Q for rev in (False, True)]
    with ThreadPoolExecutor(8) as ex:
        for tag, st in ex.map(job, jobs): print(tag, st, flush=True)
