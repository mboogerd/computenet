#!/usr/bin/env python3
"""End-to-end accuracy proxy for computenet-1ow0x. Stages are cached in cache/*.jsonl (resumable).

  python3 run.py llm   --max-opus-usd 12     # Opus proposals + 5 direct samples; Sol proposals + 1 direct
  python3 run.py jev   --max-jev-usd 1       # Jev: prior, model-A positions, claim plausibility, claim x class bearing
Analysis lives in analyze.py.
"""
import argparse, json, os, re, subprocess, sys, tempfile, threading, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE = HERE / "cache"; CACHE.mkdir(exist_ok=True)
QS = json.loads((HERE / "questions.json").read_text())["questions"]
LOCK = threading.Lock()

CANONICAL_RULES = """Write each argument in canonical form, so that it can be judged true or false on its own:
1. ONE proposition: a single subject-predicate assertion. No second clause that draws a conclusion ("…, which shows…", "…, suggesting…", "…, so…", "…, making…", "because…"), and no list of separate reasons joined by "and".
2. State the REASON, not its bearing on the claim. Give the fact, finding or principle itself; never add that it supports, undermines, is evidence for, or is best explained by the claim's thesis. How strongly it bears on the claim is judged separately.
3. EXPLICIT SUBJECT: name every person, place, group and thing ("the Riverton municipal pool", "adults over 65", "the Model K2 kettle"). No pronouns or references to things outside the sentence ("he", "this", "these trials", "such bans").
4. EXPLICIT SCOPE: say how many, how often, where, for whom ("most", "in at least five depots", "in trials of adults aged 40–65"). Do not overgeneralise ("all", "always", "never") unless that is literally true.
5. NO HEDGES: no "can", "may", "might", "could", "often", "tends to", "suggests", "some evidence". If uncertainty or frequency is the point, state it as a quantity or proportion ("in 3 of 11 trials", "in roughly a third of cases").
6. DATED ONLY WHEN IT MATTERS: for an event or a state that changes over time (office-holders, prices, opening hours, current rules, the latest research), name the year or period ("in the 2024 season", "as of 2025"). Do not date timeless facts.
7. ACCURATE AND CHECKABLE: prefer established, checkable facts; never invent studies, numbers or quotes. Give precise numbers or citations only when they are widely known. If unsure of a detail, drop the detail rather than guess.
8. SHORT: at most 25 words. Do not restate the claim's own wording."""

def options_block(q):
    return "\n".join(f"- {cid}: {txt}" for cid, txt in q["classes"].items())

def propose_prompt(q):
    return f"""You are helping a deliberation system decide a multiple-choice question. Exactly one of the candidate answers below is correct.

Question: {q['root_question']}
Candidate answers:
{options_block(q)}

Propose 10 argument claims (facts, findings, track records, base rates, principles) that bear on WHICH candidate answer is correct. Mix them: some that favour particular candidates, some that count against particular candidates, and especially some that DISCRIMINATE between the leading candidates. Each claim is judged separately for truth and for how it bears on each candidate, so do not say which answer it supports and do not state a forecast or a conclusion.

{CANONICAL_RULES}

Output ONLY a JSON array of 10 strings. No other text."""

def direct_prompt(q):
    return f"""Exactly one of the candidate answers below is correct. Give your probability distribution over them, using only your own knowledge.

Question: {q['root_question']}
Candidate answers:
{options_block(q)}

Output ONLY one JSON object mapping every candidate ID to a probability; the probabilities must sum to 1. No other text."""

# ------------------------------------------------------------------ cache
def cache_load(name):
    p = CACHE / f"{name}.jsonl"
    out = {}
    if p.exists():
        for line in p.open():
            r = json.loads(line); out[r["key"]] = r
    return out

def cache_put(name, rec):
    with LOCK, (CACHE / f"{name}.jsonl").open("a") as f:
        f.write(json.dumps(rec) + "\n")

# ------------------------------------------------------------------ LLM CLIs
def run_opus(prompt):
    with tempfile.TemporaryDirectory() as d:
        cmd = ["claude", "-p", "--output-format", "json", "--safe-mode", "--restricted", "--tools", "",
               "--disable-slash-commands", "--strict-mcp-config", "--permission-prompts", "none",
               "--no-session-persistence", "--model", "opus", "--", prompt]
        r = subprocess.run(cmd, cwd=d, capture_output=True, text=True, timeout=240, stdin=subprocess.DEVNULL)
    env = json.loads(r.stdout)
    if env.get("is_error"):
        e = RuntimeError(f"opus error: {str(env)[:300]}"); e.cost = float(env.get("total_cost_usd") or 0.0); raise e
    return env["result"], float(env.get("total_cost_usd") or 0.0), env.get("usage")

def run_sol(prompt):
    with tempfile.TemporaryDirectory() as d:
        out = Path(d) / "out.txt"
        cmd = ["codex", "exec", "--skip-git-repo-check", "--ephemeral", "--ignore-user-config", "--ignore-rules",
               "--strict-config", "--disable", "shell_tool", "--disable", "unified_exec", "--disable", "multi_agent",
               "--disable", "apps", "--disable", "view_image", "--disable", "image_generation", "--disable", "browser_use",
               "--disable", "computer_use", "--disable", "in_app_browser", "-s", "read-only",
               "-c", 'web_search="disabled"', "-c", 'model_reasoning_effort="none"', "-m", "gpt-5.6-sol",
               "--json", "-o", str(out), "--", prompt]
        r = subprocess.run(cmd, cwd=d, capture_output=True, text=True, timeout=240, stdin=subprocess.DEVNULL)
        text = out.read_text() if out.exists() else ""
    usage = [json.loads(l)["usage"] for l in r.stdout.splitlines() if '"turn.completed"' in l]
    if not text:
        raise RuntimeError(f"sol no output: {r.stderr[-300:]}")
    return text, 0.0, usage

def parse_array(text):
    m = re.search(r"\[.*\]", text, re.S)
    arr = json.loads(m.group(0))
    return [s.strip() for s in arr if isinstance(s, str) and s.strip()]

def parse_dist(text, q):
    m = re.search(r"\{.*\}", text, re.S)
    d = json.loads(m.group(0))
    p = {c: max(float(d.get(c, 0.0)), 0.0) for c in q["classes"]}
    t = sum(p.values())
    return {c: v / t for c, v in p.items()} if t > 0 else {c: 1 / len(p) for c in p}

def cmd_llm(a):
    jobs = []
    for q in QS:
        jobs.append(("opus", "propose", q, 0))
        for s in range(a.samples):
            jobs.append(("opus", "direct", q, s))
        jobs.append(("sol", "propose", q, 0))
        jobs.append(("sol", "direct", q, 0))
    done = cache_load("llm")
    spent = [sum(r.get("cost", 0) for r in done.values() if r["model"] == "opus")]
    print(f"opus spent so far ${spent[0]:.3f}")
    todo = [j for j in jobs if f"{j[0]}|{j[1]}|{j[2]['id']}|{j[3]}" not in done]
    if a.only:
        todo = [j for j in todo if j[0] == a.only]
    print(f"{len(todo)} LLM calls to make")

    def one(j):
        model, kind, q, s = j
        key = f"{model}|{kind}|{q['id']}|{s}"
        if model == "opus" and spent[0] > a.max_opus_usd:
            return key, "SKIPPED (cap)"
        prompt = propose_prompt(q) if kind == "propose" else direct_prompt(q)
        for attempt in range(3):
            try:
                text, cost, usage = (run_opus if model == "opus" else run_sol)(prompt)
                parsed = parse_array(text) if kind == "propose" else parse_dist(text, q)
                break
            except Exception as e:
                err = e
                with LOCK: spent[0] += getattr(e, "cost", 0.0)
        else:
            return key, f"FAILED {err}"
        with LOCK:
            if model == "opus": spent[0] += cost
        cache_put("llm", {"key": key, "model": model, "kind": kind, "qid": q["id"], "sample": s,
                          "raw": text, "parsed": parsed, "cost": cost, "usage": usage})
        return key, f"ok ${cost:.4f} (opus total ${spent[0]:.3f})"

    with ThreadPoolExecutor(a.concurrency) as ex:
        for key, msg in ex.map(one, todo):
            print(key, msg, flush=True)
    print(f"opus total ${spent[0]:.3f}")

# ------------------------------------------------------------------ Jev
ENDPOINT = "https://api.typesafe.ai/v1/systemone"
USD_PER_M = 0.042

PLAUS_LEVELS = [
    "Almost certainly false: available facts or well-established knowledge directly contradict the claim; it would require exceptional contrary evidence to be true.",
    "Probably false: available evidence or well-established knowledge weighs against the claim, although the claim remains reasonably possible.",
    "Uncertain: evidence is absent, balanced, or conflicting, so neither truth nor falsity is more likely.",
    "Probably true: available evidence or well-established knowledge supports the claim, although meaningful uncertainty remains.",
    "Almost certainly true: available facts or well-established knowledge directly support the claim; it would require exceptional contrary evidence to be false.",
]
def plaus_questions():
    return {
        "plausibility": {"type": "score", "instructions":
            "How likely is `claim` to be true? Judge only what `claim` itself asserts, using your general knowledge. "
            "`root_question` only tells you what topic the claim is about: do not reward or penalise `claim` for which answer "
            "to `root_question` it favours, and do not assume any other claim is true or false. If `claim` is phrased as a "
            "question, judge how likely its answer is yes.", "criteria": PLAUS_LEVELS},
        "knowledge": {"type": "choice", "instructions": {"question":
            "Can you judge how likely `claim` is to be true from your own general knowledge? Answer OUTSIDE_MY_KNOWLEDGE only "
            "when it concerns events, facts or details you have no knowledge of (for example, something too recent or too "
            "obscure), not merely when the evidence is mixed."},
            "criteria": {"WITHIN_MY_KNOWLEDGE": "Within my knowledge: I know enough about what `claim` asserts to judge it, even if the answer is uncertain or contested.",
                         "OUTSIDE_MY_KNOWLEDGE": "Outside my knowledge: judging `claim` needs facts or events I have no knowledge of."}},
    }

sys.path.insert(0, str(HERE.parent.parent / "pilot"))
import pilot as P  # bear_q, prior_qs

def other_extra(q, cid):
    if cid != "OTHER":
        return None
    return {"the_other_answers": [t for c, t in q["classes"].items() if c != "OTHER"]}

def post(body, key):
    data = json.dumps(body).encode()
    for i in range(6):
        req = urllib.request.Request(ENDPOINT, data=data, method="POST",
                                     headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            if e.code in (429, 529, 500, 502, 503) and i < 5:
                time.sleep(0.5 * 2 ** i); continue
            raise
        except (urllib.error.URLError, TimeoutError):
            if i < 5: time.sleep(1 + i); continue
            raise

def position_text(q, cid):
    if cid == "OTHER":
        others = "; ".join(t for c, t in q["classes"].items() if c != "OTHER")
        return f"The correct answer to the question \"{q['root_question']}\" is none of: {others}."
    return f"The correct answer to the question \"{q['root_question']}\" is: {q['classes'][cid]}."

def jev_requests():
    llm = cache_load("llm")
    reqs = []
    for q in QS:
        for p in ("A", "B"):
            qs = P.prior_qs(q, p)
            # prior_noul for OTHER: name the other answers
            if "OTHER" in q["classes"]:
                qs["prior_noul|OTHER"]["instructions"]["the_other_answers"] = other_extra(q, "OTHER")["the_other_answers"]
            reqs.append((f"PRIOR|{q['id']}|{p}", {"root_question": q["root_question"]}, qs))
        for cid in q["classes"]:
            reqs.append((f"POS|{q['id']}|{cid}", {"root_question": q["root_question"], "claim": position_text(q, cid)}, plaus_questions()))
        for model in ("opus", "sol"):
            rec = llm.get(f"{model}|propose|{q['id']}|0")
            if not rec:
                continue
            for i, claim in enumerate(rec["parsed"][:12]):
                base = {"root_question": q["root_question"], "claim": claim}
                reqs.append((f"PL|{model}|{q['id']}|{i}", base, plaus_questions()))
                for p in ("A", "B"):
                    qs = {f"bear|{cid}": P.bear_q(txt, p, other_extra(q, cid)) for cid, txt in q["classes"].items()}
                    reqs.append((f"BEAR|{model}|{q['id']}|{i}|{p}", base, qs))
    return reqs

def cmd_jev(a):
    key = os.environ.get("TYPESAFE_API_KEY") or sys.exit("TYPESAFE_API_KEY not set")
    done = cache_load("jev")
    toks = [sum((r["response"].get("usage") or {}).get("input_tokens", 0) for r in done.values())]
    todo = [r for r in jev_requests() if r[0] not in done]
    print(f"{len(todo)} Jev requests; spent so far ~${toks[0] * USD_PER_M / 1e6:.4f}")

    def one(r):
        rid, state, qs = r
        if toks[0] * USD_PER_M / 1e6 > a.max_jev_usd:
            return rid, "SKIPPED"
        try:
            resp = post({"model": "jev-latest", "state": state, "questions": qs}, key)
        except Exception as e:
            return rid, f"FAILED {e}"
        with LOCK:
            toks[0] += (resp.get("usage") or {}).get("input_tokens", 0)
        cache_put("jev", {"key": rid, "response": resp})
        return rid, "ok"

    fails = 0
    with ThreadPoolExecutor(a.concurrency) as ex:
        for rid, msg in ex.map(one, todo):
            if msg != "ok":
                fails += 1; print(rid, msg)
    print(f"done; fails={fails}; Jev ~${toks[0] * USD_PER_M / 1e6:.4f} ({toks[0]} input tokens)")

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); sub = ap.add_subparsers(dest="cmd", required=True)
    l = sub.add_parser("llm"); l.add_argument("--max-opus-usd", type=float, default=12); l.add_argument("--samples", type=int, default=5)
    l.add_argument("--concurrency", type=int, default=6); l.add_argument("--only")
    j = sub.add_parser("jev"); j.add_argument("--max-jev-usd", type=float, default=1.0); j.add_argument("--concurrency", type=int, default=8)
    a = ap.parse_args()
    {"llm": cmd_llm, "jev": cmd_jev}[a.cmd](a)
