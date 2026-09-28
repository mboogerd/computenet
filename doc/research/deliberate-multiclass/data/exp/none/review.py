import subprocess, glob, os, re, json, concurrent.futures as cf
D = os.path.dirname(os.path.abspath(__file__))
design = open(f"{D}/design.md").read()
ASK = """You are an independent adversarial reviewer. Below is a design + simulation for a "none of these"
(Ω) answer in a deliberation system that turns LLM-proposed, judge-scored arguments into a probability
distribution over K listed answers. Requirements: R1 exact continuity with today's binary layers when K=2 and
none-of-these is not in play; R2 two-sided debate over listed classes does not raise P(none); R3 when every
listed class is implausible or eliminated, P(none) is high and visible; R4 a class added mid-deliberation
has a sensible start; R5 none-of-these is itself argued and judged, not a fudge constant; R6 VoI can price a
claim that could establish/refute none.

Attack the RECOMMENDED design (H-hurdle+D). Find: math errors, requirement violations the numbers hide,
scenarios where it fails badly, whether a different candidate should be recommended, and whether the
simulation (especially the simulated judge) makes the conclusion circular. Be concrete (numbers,
counterexamples). End with a one-line VERDICT: ACCEPT / ACCEPT-WITH-CHANGES / REJECT, and a numbered list of
objections ranked by severity. Max 700 words.
"""
def sol():
    p = ASK + "\nThe code is in the current directory (none.py, run.py, volcano.py, out.md, volcano.out); read it to check claims.\n\n" + design
    r = subprocess.run(["codex", "exec", "--skip-git-repo-check", "--ephemeral", "-s", "read-only", "-m", "gpt-5.6-sol",
                        "-o", f"{D}/sol.md", "-"], input=p, capture_output=True, text=True, cwd=D, timeout=3000)
    open(f"{D}/sol.log", "w").write(r.stdout + "\n---STDERR---\n" + r.stderr)
    return "sol", r.returncode
def opus():
    found = glob.glob(os.path.expanduser("~/Library/Application Support/Claude/claude-code/*/claude.app/Contents/MacOS/claude"))
    key = lambda p: [int(x) for x in re.findall(r"/claude-code/([\d.]+)/", p)[0].split(".")]
    cb = max(found, key=key) if found else "claude"
    code = open(f"{D}/none.py").read()
    p = ASK + "\n\n" + design + "\n\n## none.py (candidate implementations)\n```python\n" + code + "\n```\n"
    r = subprocess.run([cb, "-p", "--bare", "--model", "claude-opus-5-5", "--tools", "", "--no-session-persistence",
                        "--output-format", "json"], input=p, capture_output=True, text=True, cwd=D, timeout=3000)
    open(f"{D}/opus.raw.json", "w").write(r.stdout + "\n---STDERR---\n" + r.stderr)
    try: open(f"{D}/opus.md", "w").write(json.loads(r.stdout)["result"])
    except Exception as e: return "opus", f"parse fail {e}"
    return "opus", r.returncode
with cf.ThreadPoolExecutor(2) as ex:
    for f in cf.as_completed([ex.submit(sol), ex.submit(opus)]): print(f.result())
