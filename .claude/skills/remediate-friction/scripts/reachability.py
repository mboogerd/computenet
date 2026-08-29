#!/usr/bin/env python3
"""How far a fix sits from the reading chain of the role that hit the friction.

WHY THIS EXISTS. The most expensive documented failure in this log is not a
wrong fix -- it is a correct fix filed where the reader who needs it never
looks. computenet-l5rc's remedy for the zsh `--include=*.kt` glob trap landed
in agent-execution.md, the execution-discipline reference handed to DISPATCHED
AGENTS. The trap then recurred twice on 2026-08-19 -- once for the ORCHESTRATOR
at step 3, once for a reviewer -- while a closed bead claimed it was fixed
(computenet-u0b0, computenet-rf0a). The bead's own diagnosis: the remedy "was
placed where its own reporter could never see it, and nothing in the close
reason records that mismatch."

Nothing catches that today, and it is mechanical: the skill's reading chains
ARE the links between its files. This walks them from each role's entry point
and reports the HOP DISTANCE to the file a fix touched.

  0  the role's own entry document — it reads this by definition
  1  handed to it directly by that document
  2+ reachable only by already knowing to look

THE INDEX TABLE IS NOT A READING CHAIN, and excluding it is what makes this
discriminate. SKILL.md carries a table naming all 20 references with a
"when to read" column. Counted as links, that table puts every reference one
hop from the orchestrator and the measure collapses -- every file scores 1 for
everybody, including agent-execution.md, whose row *explicitly* says it is for
dispatched agents. Excluded, the orchestrator's true distance to that file is
`unreached`, which is the correct answer and the one that reproduces l5rc.

Usage: reachability.py <file> [<file>...]           (who reads each file)
       reachability.py --for <role> <file>...       (GATE: is THAT role served?)
       reachability.py --roles                       (per role, every file + distance)
Exit: 0 = served; 1 = not served; 2 = bad usage / no such file or role.
A DECLINED file (`NO-MODEL`, see `declined()`) also exits 0 — it is not a
failure, but it is not a pass either, so read the output, never just the
status: a `--for` run whose files ALL decline exits 0 having asserted nothing.

`--for` is the form the lane runs. A friction bead names the role that hit the
wall ("MY OWN defect as orchestrator", "the .2.1 REVIEWER reported it"); pass
that role and the files the fix touched, and a non-zero exit says the fix is in
a file that role does not read. Verified against the tree on both sides of the
repair: at b6ac83d0 (before PR #367) `--for orchestrator
references/agent-execution.md` exits 1, reproducing computenet-l5rc's defect;
after the AGENTS.md placement landed it exits 0.

ADVISORY, not a verdict. Distance <=1 is necessary, not sufficient: the
reviewer in computenet-u0b0 had agent-execution.md at distance 1 and lost a
call to the trap anyway, "because a line inside a long execution-discipline
reference is not read at the moment someone types a grep." Use it to catch the
placement zero-case, which is the one that recurs.
"""
import os
import re
import sys
from collections import deque

SKILLDIR = ".claude/skills/work"
ROLES = {
    "orchestrator":     [f"{SKILLDIR}/SKILL.md", "AGENTS.md"],
    "implementer":      [f"{SKILLDIR}/references/task.md"],
    "task-reviewer":    [f"{SKILLDIR}/references/review-task.md"],
    "feature-reviewer": [f"{SKILLDIR}/references/review-feature.md"],
    "breakdown":        [f"{SKILLDIR}/references/epic.md",
                         f"{SKILLDIR}/references/feature.md"],
}

MD = re.compile(r"[\w./-]+\.md")
# A row of SKILL.md's reference index: `| `references/foo.md` | when to read |`.
INDEX_ROW = re.compile(r"^\|\s*`references/[\w-]+\.md`\s*\|")

SKILL_OF = re.compile(r"^\.claude/skills/([\w-]+)/")


def declined(path):
    """Why this file is outside the model — or None if the walk can judge it.

    The graph above is /work's reading chain and nothing else's. Two kinds of
    file are unjudgeable by construction, and a confident NOT-READ on either is
    a FALSE NEGATIVE that sends a session chasing a placement problem it does
    not have — the failure this script exists to catch, committed by the script
    (computenet-z9tu, observed on a sync-report edit where both edited files
    came back NOT-READ).

    A skill's own SKILL.md is the one reachability fact needing no graph: every
    invocation of a skill reads it — but that is a fact about THAT skill's
    reader, not about any /work role, so only the bare form reports it
    (`trivially_served`). Under `--for` it declines like everything else.

    An absolute path matches nothing here and falls through to the graph, which
    is relative-only (`SKILLDIR`) and will answer `unreached`. Pass paths
    relative to the repo root; a `./` prefix is fine (normpath strips it).
    """
    m = SKILL_OF.match(path)
    if m and m.group(1) != "work":
        return (f"under skill '{m.group(1)}'; the role graph models /work only")
    if not path.endswith(".md"):
        return ("not a .md file; the walk follows markdown links, so a script "
                "or data file is unreachable by construction — it takes effect "
                "by being RUN, which is what a mechanical fix is for")
    return None


def trivially_served(path):
    """A non-/work skill's own SKILL.md: read by every invocation of it."""
    m = SKILL_OF.match(path)
    return bool(m and m.group(1) != "work"
                and os.path.basename(path) == "SKILL.md")


def links(path):
    """In-skill .md files `path` points at, ignoring index-table rows."""
    try:
        with open(path) as fh:
            lines = fh.readlines()
    except OSError:
        return set()
    out = set()
    for line in lines:
        if INDEX_ROW.match(line):
            continue
        for tok in MD.findall(line):
            for cand in (os.path.normpath(os.path.join(os.path.dirname(path), tok)),
                         os.path.normpath(tok)):
                if cand.startswith(SKILLDIR) and os.path.exists(cand):
                    out.add(cand)
    return out


def distances(seeds):
    """file -> hop distance from this role's entry documents (BFS)."""
    dist = {s: 0 for s in seeds if os.path.exists(s)}
    q = deque(dist)
    while q:
        cur = q.popleft()
        for nxt in links(cur):
            if nxt not in dist:
                dist[nxt] = dist[cur] + 1
                q.append(nxt)
    return dist


def main(argv):
    args = argv[1:]
    if not args:
        print("usage: reachability.py [--for <role>] <file>... | --roles",
              file=sys.stderr)
        return 2
    per_role = {r: distances(s) for r, s in ROLES.items()}

    if args[0] == "--for":
        if len(args) < 3 or args[1] not in ROLES:
            print(f"usage: reachability.py --for <{'|'.join(sorted(ROLES))}> "
                  "<file>...", file=sys.stderr)
            return 2
        role, rc = args[1], 0
        for f in args[2:]:
            f = os.path.normpath(f)
            if not os.path.exists(f):
                print(f"reachability: {f} does not exist", file=sys.stderr)
                return 2
            # No trivially_served shortcut HERE. `--for` asserts something
            # about a ROLE, and every modelled role is a /work role: answering
            # SERVED because the file is some other skill's SKILL.md would say
            # "the /work implementer that reported this reads it", which is
            # false and is precisely the computenet-l5rc shape this gate
            # exists to catch. The bare form, which asserts nothing about a
            # role, still reports it (computenet-z9tu review).
            why = declined(f)
            if why is not None:
                print(f"NO-MODEL  {f}: {why}. Check placement by hand.")
                continue
            d = per_role[role].get(f)
            if d is not None and d <= 1:
                print(f"SERVED    {role} reads {f} at {d} hop(s)")
            else:
                where = "unreached" if d is None else f"{d} hops away"
                print(f"NOT-READ  {role} does not read {f} ({where}) — the "
                      "computenet-l5rc shape: a fix the reporting role will "
                      "never see")
                rc = 1
        return rc

    if args == ["--roles"]:
        for role in sorted(per_role):
            d = per_role[role]
            print(f"{role}:")
            for f in sorted(d, key=lambda x: (d[x], x)):
                print(f"    {d[f]}  {f}")
        return 0

    rc = 0
    for f in args:
        f = os.path.normpath(f)
        if not os.path.exists(f):
            print(f"reachability: {f} does not exist", file=sys.stderr)
            return 2
        if trivially_served(f):
            print(f)
            print("    read by:   every invocation of its own skill (its "
                  "SKILL.md)")
            continue
        why = declined(f)
        if why is not None:
            print(f)
            print(f"    NO MODEL:  {why}. Check placement by hand.")
            continue
        near = sorted(r for r in per_role if per_role[r].get(f, 99) <= 1)
        far = sorted(r for r in per_role if per_role[r].get(f, 99) >= 2)
        unreached = sorted(r for r in per_role if f not in per_role[r])
        print(f)
        print(f"    read by:   {', '.join(near) if near else '(nobody at <=1 hop)'}")
        if far:
            print(f"    far (>=2): {', '.join(far)}")
        if unreached:
            print(f"    UNREACHED: {', '.join(unreached)}")
        if not near:
            print("    ^ the computenet-l5rc shape: if the role that REPORTED "
                  "this friction is not in 'read by', the fix is in the wrong file")
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv))
