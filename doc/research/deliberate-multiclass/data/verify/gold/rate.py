#!/usr/bin/env python3
"""Step 1: blind gold rating by Opus 5.5 and Sol. 4 questions x 2 orders x 2 raters = 16 calls."""
import glob, json, os, re, subprocess, sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
RAW = HERE / "raw"
TS = json.loads((HERE.parent.parent / "pilot" / "testset.json").read_text())
TIMEOUT = 1200
LEVELS = {"--", "-", "0", "+", "++"}

def claude_bin():
    found = glob.glob(os.path.expanduser(
        "~/Library/Application Support/Claude/claude-code/*/claude.app/Contents/MacOS/claude"))
    key = lambda p: [int(x) for x in re.findall(r"/claude-code/([\d.]+)/", p)[0].split(".")]
    return max(found, key=key) if found else "claude"

FRAME = {
    "Q1": "The answer classes are treated as mutually exclusive alternatives: exactly one of them is the answer.",
    "Q2": "The question asks which ONE measure to prioritise, so the classes compete as answers even though in reality a city could do several.",
    "Q3": "The answer classes compete as the single PRIMARY cause; NONE is a live answer class meaning none of the listed causes was primary.",
    "Q4": "The classes OVERLAP: more than one can apply at once. Rate each class independently: '++' means the claim settles that THIS class applies, and says nothing about the others; '--' means the claim settles that this class does not apply.",
}

def prompt(q, order):
    classes = [(c, q["classes"][c]) for c in order]
    cls = "\n".join(f"- {c}: {t}" for c, t in classes)
    claims = "\n".join(f"- {c['id']}: {c['text']}" for c in q["claims"])
    tmpl = {c["id"]: {cid: "<level>" for cid, _ in classes} for c in q["claims"][:1]}
    return f"""You are an expert rater building a gold-standard reference. Work alone, from your own knowledge.

Question: {q['root_question']}
{FRAME[q['id']]}

Answer classes:
{cls}

Claims:
{claims}

For EVERY (claim, class) cell, assume the claim is TRUE and rate how it bears on that class being the answer, with exactly one of five codes:
  "--"  rules the class out (with the claim true, the class could not be the answer except under exceptional conditions)
  "-"   counts against the class, without ruling it out
  "0"   no bearing: makes the class neither more nor less likely
  "+"   counts for the class, without settling it
  "++"  settles it (with the claim true, the class would be the answer except under exceptional conditions)
Rate only what the claim, assumed true, changes; not whether the claim is actually true and not how likely the class is overall.

For any cell you find genuinely contestable (reasonable experts could pick a different code), give a short reason naming the alternative code.

Reply with ONLY one JSON object, no prose, no code fence, of this form:
{{"ratings": {{"<claim id>": {{"<class id>": "<code>", ...}}, ...}},
  "contested": {{"<claim id>": {{"<class id>": "<short reason>"}}, ...}}}}
List classes in this order inside every claim: {', '.join(c for c, _ in classes)}. Include all {len(q['claims'])} claims and all {len(classes)} classes for each. Example shape of one ratings entry: {json.dumps(tmpl)}
"""

def call(member, text, tag):
    (RAW / f"{tag}.prompt.md").write_text(text)
    if member == "opus":
        r = subprocess.run([claude_bin(), "-p", "--bare", "--model", "claude-opus-5-5", "--tools", "",
                            "--no-session-persistence", "--output-format", "json"],
                           input=text, capture_output=True, text=True, timeout=TIMEOUT, cwd=str(RAW))
        try:
            j = json.loads(r.stdout)
        except json.JSONDecodeError:
            raise RuntimeError(f"opus {tag}: unparseable: {r.stdout[-400:]} {r.stderr[-400:]}")
        if j.get("is_error"):
            raise RuntimeError(f"opus {tag}: {j.get('result')}")
        out = j["result"]
        (RAW / f"{tag}.meta.json").write_text(json.dumps({k: v for k, v in j.items() if k != "result"}))
    else:
        last = RAW / f"{tag}.sol-last.txt"
        if last.exists():
            last.unlink()
        r = subprocess.run(["codex", "exec", "--skip-git-repo-check", "--ephemeral", "-s", "read-only",
                            "-m", "gpt-5.6-sol", "-o", str(last), "-"],
                           input=text, capture_output=True, text=True, timeout=TIMEOUT, cwd=str(RAW))
        if r.returncode != 0 or not last.exists():
            raise RuntimeError(f"sol {tag}: exit {r.returncode}: {r.stderr[-600:]}")
        out = last.read_text()
    (RAW / f"{tag}.reply.md").write_text(out)
    return out

def parse(text, q):
    for m in re.finditer(r"\{", text):
        try:
            j = json.JSONDecoder().raw_decode(text[m.start():])[0]
        except json.JSONDecodeError:
            continue
        if "ratings" in j:
            break
    else:
        raise ValueError("no JSON")
    for c in q["claims"]:
        for cid in q["classes"]:
            v = j["ratings"][c["id"]][cid]
            if v not in LEVELS:
                raise ValueError(f"bad level {c['id']}/{cid}: {v!r}")
    return j

def job(args):
    member, q, rev = args
    order = list(q["classes"])[::-1] if rev else list(q["classes"])
    tag = f"{member}-{q['id']}-{'rev' if rev else 'fwd'}"
    dest = HERE / "ratings" / f"{tag}.json"
    if dest.exists():
        return tag, "cached"
    err = None
    for attempt in range(2):
        try:
            j = parse(call(member, prompt(q, order), tag + (f".try{attempt}" if attempt else "")), q)
            dest.parent.mkdir(exist_ok=True)
            dest.write_text(json.dumps({"order": order, **j}, indent=1))
            return tag, "ok"
        except Exception as e:
            err = e
    return tag, f"FAILED: {err}"

if __name__ == "__main__":
    RAW.mkdir(exist_ok=True)
    jobs = [(m, q, rev) for m in ("opus", "sol") for q in TS["questions"] for rev in (False, True)]
    if len(sys.argv) > 1 and sys.argv[1] == "--print-one":
        print(prompt(TS["questions"][3], list(TS["questions"][3]["classes"])))
        sys.exit()
    with ThreadPoolExecutor(8) as ex:
        for tag, st in ex.map(job, jobs):
            print(tag, st, flush=True)
