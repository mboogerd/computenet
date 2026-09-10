#!/usr/bin/env python3
"""Does each declared line-budget delta EQUAL the branch's measured delta?

validate-skills.rb's ratchet is a CEILING: it asserts `total <= budget` and
nothing else. Two consequences, both measured in one drain (computenet-3yvbd):

  - A restructuring edit that silently DELETED 56 lines of unrelated prose from
    bd-traps.md passed every gate, because deleting puts you comfortably under
    budget. The loss was found by a reviewer diffing against the merge-base.
  - A declared `work +14` against a measured +17 also passed, by quietly
    consuming three lines of pre-existing headroom.

The declared number in `line-budget.d/<id>.txt` is free text that nothing ever
compared against reality, so both directions were invisible. This compares
them. It does not replace the ceiling — a change can be honestly declared and
still be growth someone should think about; that is what the ceiling is for.

Measured the way validate-skills.rb prices: a skill is SKILL.md's body below
the frontmatter plus every `references/*.md`; AGENTS.md is the whole file.
Deltas count only from `line-budget.d` files this branch ADDED or CHANGED —
untouched ones are already inside the budget on both sides.

Runs against the MERGE-BASE with the base ref, never the base ref's tip: main
advances during a drain, and a plain `origin/main` diff shows other merged PRs'
lines as this branch's deletions (the trap AGENTS.md records for pathspecs,
arriving through a different door).

Usage: budget-delta.py [base-ref]          (default origin/main)
Exit 0: every declared delta matches, or nothing priced changed.
     1: at least one mismatch — the message names the file and both numbers.
     2: bad usage / not a git repo / no such ref.
"""
import os
import pathlib
import re
import subprocess
import sys


def git(*args, check=True):
    r = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        print(f"budget-delta: git {' '.join(args)}: {r.stderr.strip()}", file=sys.stderr)
        sys.exit(2)
    return r


def count_body(text):
    """SKILL.md is priced below its frontmatter; everything else whole."""
    return len(re.sub(r"\A---\n.*?\n---\n", "", text, flags=re.S).splitlines())


def at_base(base, path):
    r = git("show", f"{base}:{path}", check=False)
    return r.stdout if r.returncode == 0 else None


def priced_names(root):
    """The names line-budget.txt prices — the authority on what is measured."""
    names = []
    with open(os.path.join(root, ".claude/skills/line-budget.txt"), encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip()
            if line:
                names.append(line.split()[0])
    return names


def measure(root, name, base):
    """(base_total, head_total) for one priced name. Missing side counts 0."""
    if name == "AGENTS.md":
        paths = ["AGENTS.md"]
        body_of = {"AGENTS.md": False}
    else:
        d = f".claude/skills/{name}"
        skill = f"{d}/SKILL.md"
        # The worktree side is globbed, not `git ls-files`: a reference file
        # added but not yet committed is exactly what a mid-drain run sees,
        # and pricing the worktree while listing only tracked files would
        # measure two different trees.
        refs = {p for p in git("ls-tree", "-r", "--name-only", base).stdout.splitlines()
                if p.startswith(f"{d}/references/") and p.endswith(".md")
                and "/" not in p[len(f"{d}/references/"):]}
        refs |= {os.path.relpath(str(q), root)
                 for q in pathlib.Path(root, d, "references").glob("*.md")}
        paths = [skill] + sorted(refs)
        body_of = {p: (p == skill) for p in paths}

    totals = [0, 0]
    for p in paths:
        for i, text in enumerate((at_base(base, p), _worktree(root, p))):
            if text is None:
                continue
            totals[i] += count_body(text) if body_of[p] else len(text.splitlines())
    return totals[0], totals[1]


def _worktree(root, path):
    full = os.path.join(root, path)
    if not os.path.exists(full):
        return None
    with open(full, encoding="utf-8") as fh:
        return fh.read()


def declared(root, base):
    """Sum the deltas in line-budget.d files this branch added or changed."""
    # Tracked changes AND untracked additions. A delta file written but not
    # yet `git add`ed is the normal state when this runs before the commit,
    # and reading only the tracked half would report every honest declaration
    # as missing — the false alarm that gets a gate ignored.
    changed = set(git("diff", "--name-only", base, "--",
                      ".claude/skills/line-budget.d").stdout.split())
    changed |= {q for q in git("ls-files", "--others", "--exclude-standard", "--",
                               ".claude/skills/line-budget.d").stdout.split()}
    changed = sorted(changed)
    out = {}
    for rel in changed:
        text = _worktree(root, rel)
        if text is None:          # deleted by this branch: its lines leave the budget
            text = at_base(base, rel) or ""
            sign = -1
        else:
            sign = 1
        for line in text.splitlines():
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) == 2:
                try:
                    out[parts[0]] = out.get(parts[0], 0) + sign * int(parts[1])
                except ValueError:
                    pass
    return out


def main():
    argv = sys.argv[1:]
    if len(argv) > 1:
        print(__doc__.strip().splitlines()[-4], file=sys.stderr)
        return 2
    base_ref = argv[0] if argv else "origin/main"
    root = git("rev-parse", "--show-toplevel").stdout.strip()
    if git("rev-parse", "--verify", "-q", base_ref, check=False).returncode != 0:
        print(f"budget-delta: no such ref: {base_ref}", file=sys.stderr)
        return 2
    base = git("merge-base", base_ref, "HEAD").stdout.strip()

    dec = declared(root, base)
    failures = 0
    checked = 0
    for name in priced_names(root):
        b, h = measure(root, name, base)
        measured = h - b
        d = dec.get(name, 0)
        if measured == 0 and d == 0:
            continue
        checked += 1
        if measured == d:
            print(f"OK        {name}: {measured:+d} measured, {d:+d} declared")
        else:
            failures += 1
            print(f"MISMATCH  {name}: {measured:+d} measured, {d:+d} declared "
                  f"— write `{name} {measured:+d}` in "
                  f".claude/skills/line-budget.d/<bead-id>.txt and say what it "
                  f"bought (a NEGATIVE number is the right answer when the "
                  f"change removes text on purpose; an unexplained one means "
                  f"prose was lost)")
    print(f"budget-delta: {checked} priced name(s) changed against {base[:9]}, "
          f"{failures} mismatching")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
