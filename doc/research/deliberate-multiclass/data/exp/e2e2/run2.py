#!/usr/bin/env python3
"""e2e2 for computenet-1ow0x: knowledge-domain set + old forecasting set. Resumable caches in cache/.

  python3 run2.py llm  --max-opus-usd 10     # knowledge set: Opus propose + 5 direct, Sol direct (1)
  python3 run2.py jev  --max-jev-usd 1       # knowledge set: PL, POS; both sets: ISO "instr" bear (A,B) per Opus claim x class
Old forecasting set: LLM + PL + POS reused from ../e2e/cache (read-only).
"""
import argparse, glob, json, os, re, subprocess, sys, tempfile, threading, time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
E2E = HERE.parent / "e2e"
CACHE = HERE / "cache"; CACHE.mkdir(exist_ok=True)
sys.path.insert(0, str(E2E)); sys.path.insert(0, str(HERE.parent / "leak"))
import run as R          # prompts, Jev helpers (old e2e)
import part_b as PB      # instr_q (ISO bear + "don't judge overall plausibility")

KQS = json.loads((HERE / "questions_knowledge.json").read_text())["questions"]
FQS = json.loads((E2E / "questions.json").read_text())["questions"]
for q in KQS: q["domain"] = "knowledge"
for q in FQS: q["domain"] = "forecast"
LOCK = threading.Lock()

def cache_load(name, d=CACHE):
    out = {}
    p = d / f"{name}.jsonl"
    if p.exists():
        for line in p.open():
            r = json.loads(line); out[r["key"]] = r
    return out

def cache_put(name, rec):
    with LOCK, (CACHE / f"{name}.jsonl").open("a") as f:
        f.write(json.dumps(rec) + "\n")

def claude_bin():  # as .claude/skills/deliberate/scripts/deliberate.py
    found = glob.glob(os.path.expanduser("~/Library/Application Support/Claude/claude-code/*/claude.app/Contents/MacOS/claude"))
    key = lambda p: [int(x) for x in re.findall(r"/claude-code/([\d.]+)/", p)[0].split(".")]
    return max(found, key=key) if found else "claude"

def run_opus(prompt):
    with tempfile.TemporaryDirectory() as d:
        r = subprocess.run([claude_bin(), "-p", "--bare", "--model", "claude-opus-5-5", "--tools", "",
                            "--no-session-persistence", "--output-format", "json"],
                           input=prompt, capture_output=True, text=True, timeout=240, cwd=d)
    j = json.loads(r.stdout)
    if j.get("is_error"):
        e = RuntimeError(f"opus: {str(j)[:300]}"); e.cost = float(j.get("total_cost_usd") or 0); raise e
    return j["result"], float(j.get("total_cost_usd") or 0.0), j.get("usage")

def run_sol(prompt):
    with tempfile.TemporaryDirectory() as d:
        last = Path(d) / "last.txt"
        r = subprocess.run(["codex", "exec", "--skip-git-repo-check", "--ephemeral", "-s", "read-only",
                            "-m", "gpt-5.6-sol", "-o", str(last), "-"],
                           input=prompt, capture_output=True, text=True, timeout=300, cwd=d)
        if r.returncode != 0 or not last.exists():
            raise RuntimeError(f"sol exit {r.returncode}: {r.stderr[-300:]}")
        return last.read_text(), 0.0, None

def cmd_llm(a):
    jobs = []
    for q in KQS:
        jobs.append(("opus", "propose", q, 0))
        for s in range(5): jobs.append(("opus", "direct", q, s))
        jobs.append(("sol", "direct", q, 0))
    done = cache_load("llm")
    spent = [sum(r.get("cost", 0) for r in done.values() if r["model"] == "opus")]
    todo = [j for j in jobs if f"{j[0]}|{j[1]}|{j[2]['id']}|{j[3]}" not in done]
    if a.only: todo = [j for j in todo if j[0] == a.only]
    print(f"opus spent so far ${spent[0]:.3f}; {len(todo)} calls to make", flush=True)

    def one(j):
        model, kind, q, s = j
        key = f"{model}|{kind}|{q['id']}|{s}"
        if model == "opus" and spent[0] > a.max_opus_usd: return key, "SKIPPED (cap)"
        prompt = R.propose_prompt(q) if kind == "propose" else R.direct_prompt(q)
        err = None
        for attempt in range(2):  # one retry
            try:
                text, cost, usage = (run_opus if model == "opus" else run_sol)(prompt)
                with LOCK: spent[0] += cost if model == "opus" else 0
                parsed = R.parse_array(text) if kind == "propose" else R.parse_dist(text, q)
                break
            except Exception as e:
                err = e
                with LOCK: spent[0] += getattr(e, "cost", 0.0)
        else:
            return key, f"FAILED {str(err)[:200]}"
        cache_put("llm", {"key": key, "model": model, "kind": kind, "qid": q["id"], "sample": s,
                          "raw": text, "parsed": parsed, "cost": cost, "usage": usage})
        return key, f"ok ${cost:.4f} (opus total ${spent[0]:.3f})"

    with ThreadPoolExecutor(a.concurrency) as ex:
        for key, msg in ex.map(one, todo): print(key, msg, flush=True)
    print(f"opus total ${spent[0]:.3f}", flush=True)

def claims(q):
    rec = (cache_load("llm") if q["domain"] == "knowledge" else OLD_LLM).get(f"opus|propose|{q['id']}|0")
    return rec["parsed"][:12] if rec else None

OLD_LLM = cache_load("llm", E2E / "cache")

def jev_requests():
    reqs = []
    for q in KQS + FQS:
        cl = claims(q)
        if q["domain"] == "knowledge":
            for cid in q["classes"]:
                reqs.append((f"POS|{q['id']}|{cid}", {"root_question": q["root_question"], "claim": R.position_text(q, cid)}, R.plaus_questions()))
        if not cl: continue
        for i, c in enumerate(cl):
            base = {"root_question": q["root_question"], "claim": c}
            if q["domain"] == "knowledge":
                reqs.append((f"PL|opus|{q['id']}|{i}", base, R.plaus_questions()))
            for p in ("A", "B"):
                qs = {f"bear|{cid}": PB.instr_q(txt, p, R.other_extra(q, cid)) for cid, txt in q["classes"].items()}
                reqs.append((f"BEARI|opus|{q['id']}|{i}|{p}", base, qs))
    return reqs

def cmd_jev(a):
    key = os.environ.get("TYPESAFE_API_KEY") or sys.exit("TYPESAFE_API_KEY not set")
    done = cache_load("jev")
    toks = [sum((r["response"].get("usage") or {}).get("input_tokens", 0) for r in done.values())]
    todo = [r for r in jev_requests() if r[0] not in done]
    print(f"{len(todo)} Jev requests; spent so far ~${toks[0] * R.USD_PER_M / 1e6:.4f}", flush=True)

    def one(r):
        rid, state, qs = r
        if toks[0] * R.USD_PER_M / 1e6 > a.max_jev_usd: return rid, "SKIPPED"
        try:
            resp = R.post({"model": "jev-latest", "state": state, "questions": qs}, key)
        except Exception as e:
            return rid, f"FAILED {e}"
        with LOCK: toks[0] += (resp.get("usage") or {}).get("input_tokens", 0)
        cache_put("jev", {"key": rid, "response": resp})
        return rid, "ok"
    fails = n = 0
    with ThreadPoolExecutor(a.concurrency) as ex:
        for rid, msg in ex.map(one, todo):
            n += 1
            if msg != "ok": fails += 1; print(rid, msg, flush=True)
            if n % 100 == 0: print(f"{n}/{len(todo)} ~${toks[0] * R.USD_PER_M / 1e6:.4f}", flush=True)
    print(f"done; fails={fails}; Jev ~${toks[0] * R.USD_PER_M / 1e6:.4f} ({toks[0]} input tokens)", flush=True)

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); sub = ap.add_subparsers(dest="cmd", required=True)
    l = sub.add_parser("llm"); l.add_argument("--max-opus-usd", type=float, default=10); l.add_argument("--concurrency", type=int, default=6); l.add_argument("--only")
    j = sub.add_parser("jev"); j.add_argument("--max-jev-usd", type=float, default=1.0); j.add_argument("--concurrency", type=int, default=8)
    a = ap.parse_args()
    {"llm": cmd_llm, "jev": cmd_jev}[a.cmd](a)
