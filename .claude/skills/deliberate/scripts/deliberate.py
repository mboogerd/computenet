#!/usr/bin/env python3
"""Run one council deliberation over ONE decision brief. Pure: no bd, no git.

  deliberate.py --brief <brief.md> --out <dir> [--dry-run]

Phases (each member sees one decision only — never batch briefs):
  1 ideate   Opus and Sol propose options independently; Opus merges them.
  2 case     each option gets its strongest case, from its proposer.
  3 attack   the OTHER member attacks each case.
  4 score    Opus, Sol and Jev each score EVERY option 0-100 for acceptability
             (Jev: one independent P(acceptable) per option); a score below
             60 carries an objection.
  5 verdict  an option passes at mean >= 70 with no score below 60; DECIDED
             names the passing option with the highest mean (ties: higher
             minimum), else NO-CONSENSUS listing every objection. Written to
             <out>/verdict.md and verdict.json.

Every prompt and raw reply is kept in <out>/ so a person can audit the run.
Env: DELIBERATE_CLAUDE (claude binary), DELIBERATE_OPUS_MODEL
(claude-opus-5-5), DELIBERATE_SOL_MODEL (gpt-5.6-sol), TYPESAFE_API_KEY.
--dry-run replaces all three members with canned replies (tests the plumbing);
DELIBERATE_DRY_SCORES sets the canned scores (see dry_scores).
"""
import argparse, concurrent.futures as cf, glob, json, os, re, subprocess, sys, urllib.request

OPUS_MODEL = os.environ.get("DELIBERATE_OPUS_MODEL", "claude-opus-5-5")
SOL_MODEL = os.environ.get("DELIBERATE_SOL_MODEL", "gpt-5.6-sol")
TIMEOUT = 1200
PASS_MEAN = 70  # an option passes at mean score >= 70 ...
VETO = 60       # ... and no member below 60; a score below 60 is an objection
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
        # --safe-mode: no user hooks/plugins/CLAUDE.md leak a persona into a member.
        # Not --bare: it never reads OAuth, so a subscription token logs out.
        r = subprocess.run([claude_bin(), "-p", "--safe-mode", "--model", OPUS_MODEL, "--tools", "",
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


def jev_scores(brief, options, out):
    """Jev's acceptability per option: one independent Noul question each, so
    P(acceptable) is not diluted by the option count the way a softmax is."""
    state = {"decision_brief": brief,
             "options": {o["id"]: {"option": o["title"] + ": " + o["summary"], "case_for": o.get("case", ""),
                                   "case_against": o.get("attack", "")} for o in options}}
    qs = {o["id"]: {"type": "noul", "instructions":
              f"Read `decision_brief`: the problem, its context and the project's vision and principles. "
              f"`options.{o['id']}` is one option with its strongest case for and an adversarial case against. "
              f"Would adopting option {o['id']} be an acceptable call for this project — one a careful "
              f"member could live with, even if it is not their favourite?",
              "criteria": {"true": "acceptable: no serious conflict with the brief's frame or constraints",
                           "false": "unacceptable: a serious objection stands"}} for o in options}
    body = {"model": "jev-latest", "state": state, "questions": qs}
    open(f"{out}/vote.jev.request.json", "w").write(json.dumps(body, indent=1))
    if DRY:
        fx = dry_scores()
        ans = {o["id"]: {"noul": (fx["jev"][o["id"]] if fx else 90) / 100} for o in options}
    else:
        req = urllib.request.Request("https://api.typesafe.ai/v1/systemone", data=json.dumps(body).encode(),
                                     headers={"Authorization": "Bearer " + os.environ["TYPESAFE_API_KEY"],
                                              "Content-Type": "application/json"})
        resp = json.loads(urllib.request.urlopen(req, timeout=300).read())
        open(f"{out}/vote.jev.reply.json", "w").write(json.dumps(resp, indent=1))
        ans = resp["answers"]
    got = {}
    for i, a in ans.items():
        p = round(float(a["noul"]) * 100)
        got[i] = {"score": p, "objection": (f"P(acceptable) {p / 100:.2f}; Jev states no reason, so the case "
                                            f"against {i} stands as its objection") if p < VETO else ""}
    return {"scores": got}


def number(x):
    """First number in x, capped at 100: '80%' and '80-90' are 80, never 8090."""
    n = re.search(r"\d+(?:\.\d+)?", str(x))
    v = min(float(n.group()), 100.0) if n else 0.0
    return int(v) if v.is_integer() else v


def norm_id(key, ids):
    """'a', 'A ', 'Option A', 'B: Change' mean that id. Anything else, such as
    'A or B' or prose opening with the article 'a', maps to no option."""
    c = str(key).strip()
    hit = [i for i in ids if re.fullmatch(rf"(?:option\s+)?{re.escape(i)}(?:\s*[.):—-].*)?", c, re.I | re.S)]
    return hit[0] if len(hit) == 1 else None


def norm_scores(member, x, ids):
    """A member's reply -> {id: {"score", "objection"}}, one entry per option.
    A score may be a bare number or {"score", "objection"}. An option left
    unscored aborts the run: a silent gap would read as consent or as veto."""
    raw, got = x.get("scores", {}), {}
    for k, v in raw.items():
        i = norm_id(k, ids)
        if i is None:
            continue
        sc, ob = (v.get("score"), v.get("objection", "")) if isinstance(v, dict) else (v, "")
        got[i] = {"score": number(sc), "objection": (ob or "").strip()}
    missing = [i for i in ids if i not in got]
    if missing:
        raise RuntimeError(f"{member} scored no option(s) {missing}: {json.dumps(raw)[:300]}")
    for s in got.values():
        if s["score"] < VETO and not s["objection"]:
            s["objection"] = "(no objection stated)"
    x["scores"] = got
    return x


def tally(scores, ids):
    """The rule (maintainer decision 2026-09-29, computenet-hngr6). An option
    PASSES when its mean score is >= PASS_MEAN and no member scores it below
    VETO. DECIDED names the passing option with the highest mean, ties going
    to the higher minimum, then to option order. No passing option is
    NO-CONSENSUS. scores: {member: {id: {"score", "objection"}}}."""
    rows = []
    for i in ids:
        per = {m: s[i]["score"] for m, s in scores.items()}
        mean = sum(per.values()) / len(per)
        rows.append({"id": i, "scores": per, "mean": round(mean, 1), "min": min(per.values()),
                     "passes": mean >= PASS_MEAN and min(per.values()) >= VETO,
                     "objections": [{"member": m, "score": s[i]["score"], "objection": s[i]["objection"]}
                                    for m, s in scores.items() if s[i]["score"] < VETO]})
    passing = sorted((r for r in rows if r["passes"]), key=lambda r: (-r["mean"], -r["min"], ids.index(r["id"])))
    return {"outcome": "DECIDED" if passing else "NO-CONSENSUS",
            "choice": passing[0]["id"] if passing else None, "tally": rows}


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

    # 4 score — independent, parallel, Jev included; every member scores EVERY option
    dossier = json.dumps([{k: o.get(k, "") for k in ("id", "title", "summary", "case", "attack")} for o in options], indent=1)
    vote = ("You are one of three independent members scoring the decision below. Each option has its strongest "
            "case and an adversarial critique. Score EVERY option 0-100 for how ACCEPTABLE it is as this "
            "project's call: 100 = clearly right, 70 = good enough to adopt, below 60 = you object to adopting "
            "it. Several options may be acceptable. For every option you score below 60, state your objection "
            "in one sentence. Reply with ONLY JSON: "
            '{"scores": {"<id>": {"score": <0-100>, "objection": "<required if score < 60, else empty>"}, ...}, '
            '"deciding_factors": ["<the 1-3 considerations that most shaped your scores>"]}'
            f"\n\n--- BRIEF ---\n{brief}\n\n--- OPTIONS ---\n{dossier}")
    ids = [o["id"] for o in options]
    fut = {m: pool.submit(ask_json, m, vote, out, f"4-vote.{m}") for m in ("opus", "sol")}
    fut["jev"] = pool.submit(jev_scores, brief, options, out)
    votes = {m: norm_scores(m, f.result(), ids) for m, f in fut.items()}

    # 5 verdict
    t = tally({m: x["scores"] for m, x in votes.items()}, ids)
    verdict = {**t, "votes": votes, "options": options,
               "members": {"opus": OPUS_MODEL, "sol": SOL_MODEL, "jev": "jev-latest"}}
    json.dump(verdict, open(f"{out}/verdict.json", "w"), indent=1)
    open(f"{out}/verdict.md", "w").write(render(verdict))
    return verdict


def render(v):
    byid = {o["id"]: o for o in v["options"]}
    members = list(v["votes"])
    head = (f"{v['outcome']}: {v['choice']} — {byid[v['choice']]['title']}" if v["choice"]
            else "NO-CONSENSUS — a person decides")
    lines = [head, "", f"An option passes at a mean score >= {PASS_MEAN} with no member below {VETO}; "
             "DECIDED is the passing option with the highest mean (ties: higher minimum).", "",
             "| Option | " + " | ".join(f"{m} ({v['members'][m]})" for m in members) + " | Mean | Min | Passes |",
             "|---" * (len(members) + 4) + "|"]
    for r in v["tally"]:
        lines.append(f"| {r['id']} | " + " | ".join(str(r["scores"][m]) for m in members)
                     + f" | {r['mean']} | {r['min']} | {'yes' if r['passes'] else 'no'} |")
    objs = [(r["id"], o) for r in v["tally"] for o in r["objections"]]
    if objs:
        lines += ["", "Objections (every score below %d):" % VETO]
        lines += [f"- {i}, {o['member']} ({o['score']}): {o['objection']}" for i, o in objs]
    lines += ["", "Options:"] + [f"- {o['id']}. {o['title']}: {o['summary']}" for o in v["options"]]
    if v["choice"]:
        factors = [f for m in members for f in v["votes"][m].get("deciding_factors", [])]
        if factors:
            lines += ["", "Deciding factors:"] + [f"- {f}" for f in factors]
        att = byid[v["choice"]].get("attack")
        if att:
            lines += ["", f"Strongest case against {v['choice']} (weighed; no member objected): {att}"]
    return "\n".join(lines) + "\n"


def dry_scores():
    """DELIBERATE_DRY_SCORES='{"opus": {"A": 80, ...}, "sol": {...}, "jev": {...}}' (jev in 0-100)."""
    raw = os.environ.get("DELIBERATE_DRY_SCORES")
    return json.loads(raw) if raw else None


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
        return '{"scores": {"Option a": "80%", "B: Change": {"score": "80-90"}}, "deciding_factors": ["m"]}'
    if tag.startswith("4-vote") and os.environ.get("DELIBERATE_DRY_UNSCORED"):
        return '{"scores": {"A or B": 80}}'
    if tag.startswith("4-vote"):
        m = tag.split(".")[1]
        fx = dry_scores() or {m: {"A": 80, "B": 50}}
        return "```json\n" + json.dumps({"scores": {i: {"score": sc, "objection": f"{m} objects to {i}" if sc < VETO else ""}
                                                   for i, sc in fx[m].items()}, "deciding_factors": ["f"]}) + "\n```"
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
