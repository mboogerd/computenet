#!/usr/bin/env bash
# Checks SKILL.md's scripts table against the scripts themselves, and each
# script's HEADER usage against the usage it prints at runtime.
#
#   .claude/skills/work/scripts/usage-table.test.sh [path/to/SKILL.md]
#
# WHY. The table is what an orchestrator calls from, and it listed what each
# script DOES with no arguments at all, so every call was a guess or a re-read
# of the source (computenet-nrv5). Signatures alone would drift the day after
# they were written — the bead's filer said as much ("I have NOT audited the
# others") — so the signatures land with this check, which fails when a row and
# its script disagree.
#
# Three checks:
#   1. every table row carries a leading backticked signature (or
#      `(no arguments)`);
#   2. every <placeholder> and --flag in a row appears in that script's own
#      usage text — the direction that catches a row inventing a flag;
#   3. where a script states usage TWICE (a header comment and the message it
#      prints on bad arguments), no option is in the runtime one and absent
#      from the header — the header is what a reader, and this table, copies
#      from. That check found merge-task.sh's header missing [--keep-open].
#      The other direction is not enforced: a header block legitimately
#      documents more than a one-line refusal.
#
# The opposite direction of check 2 is deliberately NOT enforced:
# create-ticket.sh and file-friction.sh take more options than a table row
# should carry, and a summary is allowed to be a summary.
#
# Named for sibling-tests.sh, which runs the suites of scripts a diff changed:
# it mentions every script below OUTSIDE a comment, so a change to any of them
# runs this.
#
# Exits 0 if all checks pass, 1 otherwise.
set -uo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SKILL=${1:-"$HERE/../SKILL.md"}
[ -r "$SKILL" ] || { echo "not readable: $SKILL" >&2; exit 1; }

# The mention list sibling-tests.sh keys on. Not read by the checker, which
# globs the directory — this exists so that editing any one of them runs this.
COVERS="bead.sh check-files-claim.sh claim-epic.sh claim-item.sh
create-ticket.sh ensure-worktree.sh epic-of.sh feature-branch.sh
file-friction.sh junit-count.py merge-task.sh next-batch.py publish-beads.sh
ready-in-epic.sh reclaim-worktrees.sh resumable-epics.sh session-holder.sh
sweep-merged-prs.sh sweep-stale-claims.sh twin-scan.py verify-branch-sync.sh
wait-checks.sh"
[ -n "$COVERS" ] || exit 1

exec python3 - "$SKILL" "$HERE" <<'PY'
import re, sys, pathlib

skill = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
scripts_dir = pathlib.Path(sys.argv[2])

ROW = re.compile(r"^\|\s*`([\w.-]+\.(?:sh|py))`\s*\|\s*(.*?)\s*\|\s*$", re.M)
# A --flag or a <placeholder>. Single-letter flags count only inside a table
# signature: `echo -n` in a runtime message is not an argument of the script.
TOK = re.compile(r"<[^>]+>|--[A-Za-z][\w-]*")
SHORT = re.compile(r"<[^>]+>|--[A-Za-z][\w-]*|(?<![\w-])-[A-Za-z](?![\w-])")


def usage_blocks(path):
    """(header, runtime) usage lines. A header usage is a comment line naming a
    usage plus the indented comment lines under it: a multi-line usage keeps
    its arguments there, so stopping at the first line loses them."""
    header, runtime, collecting = [], [], False
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if re.search(r"\busage:", line, re.I):
            # Runtime = the message the script EMITS. Keying on emission rather
            # than on a leading "#" is what lets a Python docstring header count
            # as a header (twin-scan.py, junit-count.py).
            emitted = re.search(r"\becho\b|\bprint\b|\$\{", line) is not None
            (runtime if emitted else header).append(line)
            collecting = not emitted
            continue
        if collecting:
            if re.match(r"^(#|\s)\s{2,}\S", line):
                header.append(line)
            else:
                collecting = False
    return header, runtime


fails, notes = [], []
rows = ROW.findall(skill)
seen = set()

for name, desc in rows:
    path = scripts_dir / name
    if not path.exists():
        fails.append("SKILL.md row `%s` names a script that does not exist" % name)
        continue
    seen.add(name)

    sig = re.match(r"`([<\[(-][^`]*)`", desc)
    if not sig:
        fails.append("`%s` row has no signature — start it with a backticked "
                     "argument list, or `(no arguments)`" % name)
        continue

    h, r = usage_blocks(path)
    text = " ".join(h + r)
    if not text:
        notes.append("`%s` states no usage anywhere; row signature unchecked" % name)
        continue
    for tok in sorted(set(SHORT.findall(sig.group(1).replace("\\|", "|")))):
        if tok not in text:
            fails.append("`%s` row claims %s, which appears in no usage line of "
                         "the script" % (name, tok))

for path in sorted(scripts_dir.glob("*.sh")) + sorted(scripts_dir.glob("*.py")):
    if ".test." in path.name or path.name.endswith("-lib.sh"):
        continue
    if path.name not in seen:
        notes.append("`%s` has no row in SKILL.md's table" % path.name)
    header, runtime = usage_blocks(path)
    if header and runtime:
        # One direction only. A header block legitimately documents MORE than a
        # short runtime message (create-ticket.sh's full option list against a
        # one-line refusal). The drift that matters is the other way: an option
        # the script accepts and its header never mentions, which is what a
        # reader — and this table — copies from.
        missing = sorted(set(TOK.findall(" ".join(runtime)))
                         - set(TOK.findall(" ".join(header))))
        if missing:
            fails.append("`%s` accepts %s in its runtime usage and its header "
                         "block never mentions it" % (path.name, missing))

for n in notes:
    print("  NOTE " + n)
for f in fails:
    print("  FAIL " + f)
print("%d row(s) checked, %d failing, %d note(s)" % (len(rows), len(fails), len(notes)))
sys.exit(1 if fails else 0)
PY
