#!/usr/bin/env python3
"""Run one council deliberation over ONE decision brief. Pure: no bd, no git.

  deliberate.py --brief <brief.md> --out <dir> [--dry-run]

Phases (each member sees one decision only — never batch briefs):
  1 ideate   Opus and Sol propose options independently; Opus merges them.
  2 case     each option gets its strongest case, from its proposer.
  3 attack   the OTHER member attacks each case.
  4 vote     Opus, Sol and Jev each pick one option with a confidence;
             Jev sees every option with its case for and against.
  5 verdict  DECIDED iff all three agree and mean confidence >= 70,
             else NO-CONSENSUS. Written to <out>/verdict.md and verdict.json.

Every prompt and raw reply is kept in <out>/ so a person can audit the run.
Env: DELIBERATE_CLAUDE (claude binary), DELIBERATE_OPUS_MODEL
(claude-opus-5-5), DELIBERATE_SOL_MODEL (gpt-5.6-sol), TYPESAFE_API_KEY.
--dry-run replaces all three members with canned replies (tests the plumbing).
"""
import argparse, concurrent.futures as cf, glob, json, os, re, subprocess, sys, urllib.request

OPUS_MODEL = os.environ.get("DELIBERATE_OPUS_MODEL", "claude-opus-5-5")
SOL_MODEL = os.environ.get("DELIBERATE_SOL_MODEL", "gpt-5.6-sol")
TIMEOUT = 1200
CONSENSUS_MEAN = 70
DRY = False


def claude_bin():
    if os.environ.get("DELIBERATE_CLAUDE"):
        return os.environ["DELIBERATE_CLAUDE"]
    # ponytail: the desktop app ships a newer CLI than the PATH one; Opus 5.5
    # needs >= 2.1.280. Newest bundled wins, PATH claude is the fallback.
    found = glob.glob(os.path.expanduser(
        "~/Library/Application Support/Claude/claude-code/*/claude.app/Contents/MacOS/claude"))
    key = lambda p: [int(x) for x in re.findall(r"/claude-code/([\d.]+)/", p)[0].split(".")]
    return max(found, key=key) if found else "claude"


def call(member, prompt, out, tag):
    """Send one prompt to one member; save prompt and reply; return reply text."""
    open(f"{out}/{tag}.prompt.md", "w").write(prompt)
    if DRY:
        text = dry_reply(tag)
    elif member == "opus":
        # --bare: no user hooks/plugins/CLAUDE.md leak a persona into a member.
        r = subprocess.run([claude_bin(), "-p", "--bare", "--model", OPUS_MODEL, "--tools", "",
                            "--no-session-persistence", "--output-format", "json"],
                           input=prompt, capture_output=True, text=True, timeout=TIMEOUT, cwd=out)
        try:
            j = json.loads(r.stdout)
        except json.JSONDecodeError:
            raise RuntimeError(f"opus {tag}: unparseable output: {r.stdout[-400:]} {r.stderr[-400:]}")
        if j.get("is_error"):
            raise RuntimeError(f"opus {tag}: {j.get('result')}")
        text = j["result"]
    elif member == "sol":
        last = f"{out}/{tag}.sol-last.txt"
        # stdin must be the prompt: codex otherwise blocks "Reading additional input from stdin".
        r = subprocess.run(["codex", "exec", "--skip-git-repo-check", "--ephemeral", "-s", "read-only",
                            "-m", SOL_MODEL, "-o", last, "-"],
                           input=prompt, capture_output=True, text=True, timeout=TIMEOUT, cwd=out)
        if r.returncode != 0 or not os.path.exists(last):
            raise RuntimeError(f"sol {tag}: exit {r.returncode}: {r.stderr[-600:]}")
        text = open(last).read()
    else:
        raise ValueError(member)
    open(f"{out}/{tag}.reply.md", "w").write(text)
    return text


def parse_json(text):
    """First JSON object/array in a reply (members sometimes wrap it in prose or fences)."""
    for m in re.finditer(r"[\[{]", text):
        try:
            return json.JSONDecoder().raw_decode(text[m.start():])[0]
        except json.JSONDecodeError:
            continue
    raise ValueError(f"no JSON in reply: {text[:300]}")


def ask_json(member, prompt, out, tag):
    try:
        return parse_json(call(member, prompt, out, tag))
    except ValueError:  # one retry, with the format restated
        return parse_json(call(member, prompt + "\n\nYour previous reply was not valid JSON. Reply with ONLY the JSON.",
                               out, tag + ".retry"))


def jev_vote(brief, options, out):
    crit = {o["id"]: {"option": o["title"] + ": " + o["summary"],
                      "case_for": o.get("case", ""), "case_against": o.get("attack", "")} for o in options}
    body = {"model": "jev-latest", "state": {"decision_brief": brief},
            "questions": {"vote": {"type": "choice", "criteria": crit, "instructions":
                "Read `decision_brief`: the problem, its context and the project's vision and principles. "
                "Each option carries its strongest case for and an adversarial case against. "
                "Which option is the right call for this project?"}}}
    open(f"{out}/vote.jev.request.json", "w").write(json.dumps(body, indent=1))
    if DRY:
        ans = {"choice": options[0]["id"], "confidence": 0.9, "probabilities": {options[0]["id"]: 0.95}}
    else:
        req = urllib.request.Request("https://api.typesafe.ai/v1/systemone", data=json.dumps(body).encode(),
                                     headers={"Authorization": "Bearer " + os.environ["TYPESAFE_API_KEY"],
                                              "Content-Type": "application/json"})
        resp = json.loads(urllib.request.urlopen(req, timeout=300).read())
        open(f"{out}/vote.jev.reply.json", "w").write(json.dumps(resp, indent=1))
        ans = resp["answers"]["vote"]
    top = sorted(ans.get("probabilities", {}).items(), key=lambda kv: -kv[1])[:3]
    return {"choice": ans["choice"], "confidence": round(ans["confidence"] * 100),
            "reason": "distribution " + ", ".join(f"{k} {v:.2f}" for k, v in top)}


def norm_vote(x, ids):
    """'a', 'A ', 'Option A', 'B: Change' mean that id. Anything else, such as
    'A or B' or prose opening with the article 'a', stays as written, so it
    agrees with no other vote. Confidence is its first number, capped at 100:
    '80%' and '80-90' are 80, never 8090."""
    c = str(x["choice"]).strip()
    hit = [i for i in ids if re.fullmatch(rf"(?:option\s+)?{re.escape(i)}(?:\s*[.):—-].*)?", c, re.I | re.S)]
    x["choice"] = hit[0] if len(hit) == 1 else c
    n = re.search(r"\d+(?:\.\d+)?", str(x["confidence"]))
    v = min(float(n.group()), 100.0) if n else 0.0
    x["confidence"] = int(v) if v.is_integer() else v


FORMAT_OPTIONS ='[{"title": "<short name>", "summary": "<what it means and its practical consequences, 2-4 sentences>"}]'


def run(brief, out):
    os.makedirs(out, exist_ok=True)
    pool = cf.ThreadPoolExecutor(4)
    both = lambda f: dict(zip(["opus", "sol"], pool.map(f, ["opus", "sol"])))

    # 1 ideate
    ideate = ("You are one member of a small decision council. Read the decision brief below. "
              "Propose the 2-5 genuinely distinct options you think deserve consideration. "
              "Include every option the brief already names (keep its label in the title), and add any it misses. "
              f"Reply with ONLY a JSON array: {FORMAT_OPTIONS}\n\n--- BRIEF ---\n{brief}")
    ideas = both(lambda m: ask_json(m, ideate, out, f"1-ideate.{m}"))
    merge = ("Two council members proposed options for the decision below. Merge them into one list of 2-6 "
             "distinct options: combine duplicates, keep genuinely different ones, and order them so the brief's own labelled options come first, in the brief's order. Titles carry no letter prefix. "
             'For each, record who proposed it. Reply with ONLY a JSON array: '
             '[{"id": "A", "title": "...", "summary": "...", "proposed_by": ["opus"|"sol", ...]}] '
             "with ids A, B, C... in order.\n\n--- BRIEF ---\n" + brief +
             "\n\n--- OPUS PROPOSED ---\n" + json.dumps(ideas["opus"], indent=1) +
             "\n\n--- SOL PROPOSED ---\n" + json.dumps(ideas["sol"], indent=1))
    options = ask_json("opus", merge, out, "1-merge")
    for i, o in enumerate(options):  # proposer argues; shared options alternate
        pb = o.get("proposed_by") or []
        o["advocate"] = pb[0] if len(pb) == 1 else ("opus" if i % 2 == 0 else "sol")
    other = {"opus": "sol", "sol": "opus"}

    # 2 case, 3 attack — one call per member, over only its assigned options
    def phase(kind, owner_of):
        def go(m):
            mine = [o for o in options if owner_of(o) == m]
            if not mine:
                return {}
            if kind == "case":
                p = ("You are the advocate. For each option below, write the STRONGEST honest case that it is the "
                     "right call for this project, grounded in the brief's context and the project's vision. "
                     "Name the concrete consequences that favour it. 150-250 words each.")
            else:
                p = ("You are the adversary. For each option below, attack its case: the weakest assumptions, the "
                     "consequences it understates, the ways it conflicts with the project's vision or principles, "
                     "and what would make it the wrong call. Be specific and fair. 150-250 words each.")
            listing = json.dumps([{k: o[k] for k in ("id", "title", "summary", "case") if k in o} for o in mine], indent=1)
            got = ask_json(m, f'{p}\nReply with ONLY a JSON object {{"<id>": "<text>", ...}}.\n\n--- BRIEF ---\n{brief}'
                              f"\n\n--- OPTIONS ---\n{listing}", out, f"{'2-case' if kind == 'case' else '3-attack'}.{m}")
            return got
        for m, got in both(go).items():
            for o in options:
                if isinstance(got, dict) and o["id"] in got:
                    o[kind] = got[o["id"]]
        # A reply keyed by title or "Option A" would otherwise send an option to
        # the vote with no case (or no attack), and Jev would never see it.
        missing = [o["id"] for o in options if not o.get(kind)]
        if missing:
            raise RuntimeError(f"{kind} phase: no {kind} for option(s) {missing}; see the replies in {out}")
    phase("case", lambda o: o["advocate"])
    phase("attack", lambda o: other[o["advocate"]])

    # 4 vote — independent, parallel, Jev included
    dossier = json.dumps([{k: o.get(k, "") for k in ("id", "title", "summary", "case", "attack")} for o in options], indent=1)
    vote = ("You are one of three independent voters on the decision below. Each option has its strongest case "
            "and an adversarial critique. Pick the ONE option that is the right call for this project, and give "
            "your confidence 0-100 that it is right. Reply with ONLY JSON: "
            '{"choice": "<id>", "confidence": <0-100>, "reason": "<one sentence>", '
            '"deciding_factors": ["<the 1-3 considerations that decided it>"]}'
            f"\n\n--- BRIEF ---\n{brief}\n\n--- OPTIONS ---\n{dossier}")
    fut = {m: pool.submit(ask_json, m, vote, out, f"4-vote.{m}") for m in ("opus", "sol")}
    fut["jev"] = pool.submit(jev_vote, brief, options, out)
    votes = {m: f.result() for m, f in fut.items()}
    for x in votes.values():
        norm_vote(x, [o["id"] for o in options])

    # 5 verdict
    choices = {v["choice"] for v in votes.values()}
    mean = round(sum(float(v["confidence"]) for v in votes.values()) / 3)
    decided = len(choices) == 1 and mean >= CONSENSUS_MEAN
    verdict = {"outcome": "DECIDED" if decided else "NO-CONSENSUS",
               "choice": next(iter(choices)) if decided else None, "mean_confidence": mean,
               "votes": votes, "options": options,
               "members": {"opus": OPUS_MODEL, "sol": SOL_MODEL, "jev": "jev-latest"}}
    json.dump(verdict, open(f"{out}/verdict.json", "w"), indent=1)
    open(f"{out}/verdict.md", "w").write(render(verdict))
    return verdict


def render(v):
    byid = {o["id"]: o for o in v["options"]}
    head = (f"{v['outcome']}: {v['choice']} — {byid[v['choice']]['title']}" if v["choice"]
            else "NO-CONSENSUS — a person decides")
    lines = [head, "", f"Mean confidence {v['mean_confidence']} (DECIDED needs all three agreeing and a mean >= {CONSENSUS_MEAN}).", "",
             "| Member | Choice | Confidence | Reason |", "|---|---|---|---|"]
    for m, x in v["votes"].items():
        lines.append(f"| {m} ({v['members'][m]}) | {x['choice']} | {x['confidence']} | {x.get('reason', '')} |")
    lines += ["", "Options:"] + [f"- {o['id']}. {o['title']}: {o['summary']}" for o in v["options"]]
    factors = [f for m in ("opus", "sol") for f in v["votes"][m].get("deciding_factors", [])
               if v["votes"][m]["choice"] == v["choice"]] if v["choice"] else []
    if factors:
        lines += ["", "Deciding factors:"] + [f"- {f}" for f in factors]
        att = byid[v["choice"]].get("attack")
        if att:
            lines += ["", f"Strongest objection to {v['choice']} (weighed and outvoted): {att}"]
    return "\n".join(lines) + "\n"


def dry_reply(tag):
    if tag.startswith("1-ideate"):
        return json.dumps([{"title": "Keep", "summary": "Change nothing."}, {"title": "Change", "summary": "Change it."}])
    if tag.startswith("1-merge"):
        return json.dumps([{"id": "A", "title": "Keep", "summary": "Change nothing.", "proposed_by": ["opus", "sol"]},
                           {"id": "B", "title": "Change", "summary": "Change it.", "proposed_by": ["sol"]}])
    if tag.startswith("2-case") and os.environ.get("DELIBERATE_DRY_MISKEY"):
        return '{"Option A": "text A", "B": "text B"}'
    if tag.startswith("2-case") or tag.startswith("3-attack"):
        return 'Here: {"A": "text A", "B": "text B"}'
    if tag.startswith("4-vote.sol") and os.environ.get("DELIBERATE_DRY_MESSY"):
        return '{"choice": "Option a", "confidence": "80%", "reason": "r", "deciding_factors": ["m"]}'
    if tag.startswith("4-vote.sol") and os.environ.get("DELIBERATE_DRY_SPLIT"):
        return '{"choice": "B", "confidence": 90, "reason": "r", "deciding_factors": ["g"]}'
    if tag.startswith("4-vote"):
        return '```json\n{"choice": "A", "confidence": 80, "reason": "r", "deciding_factors": ["f"]}\n```'
    raise ValueError(tag)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--brief", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    DRY = a.dry_run
    v = run(open(a.brief).read(), os.path.abspath(a.out))
    print(open(os.path.join(os.path.abspath(a.out), "verdict.md")).read())
    sys.exit(0)
