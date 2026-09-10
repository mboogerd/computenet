#!/usr/bin/env python3
"""budget-delta.py: does it discriminate the two shapes that got past the ceiling?

Each case is a throwaway git repo with a two-skill layout, a base commit, and a
branch commit that changes prose. The ratchet these cases model is a CEILING, so
it passes all of them; this suite exists to show budget-delta.py does not.
"""
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "budget-delta.py")
failed = 0
cases = 0


def sh(cwd, *args):
    r = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    assert r.returncode == 0, f"{args}: {r.stderr}"
    return r.stdout


def write(root, rel, text):
    p = os.path.join(root, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(text)


def commit(root, msg):
    sh(root, "git", "add", "-A")
    sh(root, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", msg)


def fixture():
    """Base: skill `w` at 6 priced lines (2 body + 4 references), AGENTS.md at 3."""
    root = tempfile.mkdtemp()
    sh(root, "git", "init", "-q", "-b", "main", root)
    write(root, ".claude/skills/line-budget.txt", "w 6\nAGENTS.md 3\n")
    write(root, ".claude/skills/w/SKILL.md",
          "---\nname: w\n---\nbody one\nbody two\n")
    write(root, ".claude/skills/w/references/a.md", "a1\na2\na3\na4\n")
    write(root, "AGENTS.md", "g1\ng2\ng3\n")
    write(root, ".claude/skills/line-budget.d/README.txt", "notes\n")
    commit(root, "base")
    sh(root, "git", "branch", "base-ref")
    return root


def run(root):
    r = subprocess.run([sys.executable, SCRIPT, "base-ref"],
                       cwd=root, capture_output=True, text=True)
    return r.returncode, r.stdout


def case(name, build, want_rc, want_in):
    global failed, cases
    cases += 1
    root = fixture()
    build(root)
    rc, out = run(root)
    if rc != want_rc or want_in not in out:
        failed += 1
        print(f"FAIL: {name}\n  rc={rc} want {want_rc}\n  want {want_in!r} in:\n{out}")


# The 56-line silent deletion (computenet-3yvbd's first instance): prose removed,
# nothing declared. Under a ceiling this is the healthiest-looking change there is.
case("undeclared deletion",
     lambda r: (write(r, ".claude/skills/w/references/a.md", "a1\n"),
                commit(r, "lose three lines")),
     1, "MISMATCH  w: -3 measured, +0 declared")

# A deletion the author MEANT: declaring it passes.
case("declared deletion",
     lambda r: (write(r, ".claude/skills/w/references/a.md", "a1\n"),
                write(r, ".claude/skills/line-budget.d/b.txt", "w -3\n# on purpose\n"),
                commit(r, "lose three lines, declared")),
     0, "OK        w: -3 measured, -3 declared")

# The understated addition (second instance, PR #807): +3 real, +1 declared.
case("understated addition",
     lambda r: (write(r, ".claude/skills/w/references/a.md", "a1\na2\na3\na4\nn1\nn2\nn3\n"),
                write(r, ".claude/skills/line-budget.d/b.txt", "w +1\n"),
                commit(r, "understate")),
     1, "MISMATCH  w: +3 measured, +1 declared")

case("honest addition",
     lambda r: (write(r, ".claude/skills/w/references/a.md", "a1\na2\na3\na4\nn1\nn2\nn3\n"),
                write(r, ".claude/skills/line-budget.d/b.txt", "w +3\n"),
                commit(r, "honest")),
     0, "OK        w: +3 measured, +3 declared")

# SKILL.md is priced BELOW its frontmatter — a frontmatter edit must not count.
case("frontmatter is not priced",
     lambda r: (write(r, ".claude/skills/w/SKILL.md",
                      "---\nname: w\ndescription: added\n---\nbody one\nbody two\n"),
                commit(r, "frontmatter only")),
     0, "0 priced name(s) changed")

# A NEW reference file is priced too — the shape a restructure produces.
case("new reference file counts",
     lambda r: (write(r, ".claude/skills/w/references/b.md", "b1\nb2\n"),
                write(r, ".claude/skills/line-budget.d/b.txt", "w +2\n"),
                commit(r, "new ref")),
     0, "OK        w: +2 measured, +2 declared")

# AGENTS.md is priced on the same ratchet and is not a skill (no frontmatter).
case("AGENTS.md is priced whole",
     lambda r: (write(r, "AGENTS.md", "g1\ng2\ng3\ng4\ng5\n"),
                commit(r, "grow AGENTS.md")),
     1, "MISMATCH  AGENTS.md: +2 measured, +0 declared")

# An UNTOUCHED delta file is already inside the budget on both sides: it must
# not be counted as this branch's declaration.
case("untouched delta file is not a declaration",
     lambda r: (write(r, ".claude/skills/line-budget.d/old.txt", "w +5\n"),
                commit(r, "pre-existing delta"),
                sh(r, "git", "branch", "-f", "base-ref", "HEAD"),
                write(r, ".claude/skills/w/references/a.md", "a1\na2\na3\na4\nn1\n"),
                commit(r, "grow without declaring")),
     1, "MISMATCH  w: +1 measured, +0 declared")

# Scripts are unpriced: a script-only PR declares nothing and must pass.
case("scripts are unpriced",
     lambda r: (write(r, ".claude/skills/w/scripts/x.sh", "#!/bin/sh\necho hi\n"),
                commit(r, "script only")),
     0, "0 priced name(s) changed")

# UNCOMMITTED state is the normal state when this runs — step 4 gates before the
# commit. Measuring the worktree while reading declarations only from tracked
# changes reported every honest declaration as missing; found by running the new
# gate against its own PR.
case("uncommitted change and uncommitted declaration",
     lambda r: (write(r, ".claude/skills/w/references/a.md", "a1\na2\na3\na4\nn1\n"),
                write(r, ".claude/skills/line-budget.d/b.txt", "w +1\n")),
     0, "OK        w: +1 measured, +1 declared")

case("uncommitted change with no declaration still fails",
     lambda r: write(r, ".claude/skills/w/references/a.md", "a1\na2\na3\na4\nn1\n"),
     1, "MISMATCH  w: +1 measured, +0 declared")

# A brand-new reference file, uncommitted: globbed, not listed from the index.
case("uncommitted new reference file counts",
     lambda r: (write(r, ".claude/skills/w/references/b.md", "b1\nb2\n"),
                write(r, ".claude/skills/line-budget.d/b.txt", "w +2\n")),
     0, "OK        w: +2 measured, +2 declared")

# The declaration is the CHANGE in the declared budget, not the value of the
# delta files this branch touched. These six cases are the difference, and each
# was a false alarm found by running both gates over the same repo.

# FOLD-BACK — delete the delta file, raise the base row by the same amount. The
# README and validate-skills.rb both prescribe it, and nothing about it moves a
# line of prose, so it must be a no-op here.
case("fold-back into the base ledger declares nothing",
     lambda r: (write(r, ".claude/skills/line-budget.d/old.txt", "w +5\n"),
                commit(r, "pre-existing delta"),
                sh(r, "git", "branch", "-f", "base-ref", "HEAD"),
                os.remove(os.path.join(r, ".claude/skills/line-budget.d/old.txt")),
                write(r, ".claude/skills/line-budget.txt", "w 11\nAGENTS.md 3\n"),
                commit(r, "fold back")),
     0, "0 priced name(s) changed")

# A delta file DELETED with the prose it bought is a real negative declaration —
# the case the fold-back must not be confused with.
case("deleting a delta file with its prose is a declared deletion",
     lambda r: (write(r, ".claude/skills/w/references/a.md", "a1\na2\na3\na4\nn1\nn2\n"),
                write(r, ".claude/skills/line-budget.d/old.txt", "w +2\n"),
                commit(r, "grow, declared"),
                sh(r, "git", "branch", "-f", "base-ref", "HEAD"),
                write(r, ".claude/skills/w/references/a.md", "a1\na2\na3\na4\n"),
                os.remove(os.path.join(r, ".claude/skills/line-budget.d/old.txt")),
                commit(r, "revert both")),
     0, "OK        w: -2 measured, -2 declared")

# Rewording an OLD delta file's comment does not re-declare its number.
case("editing an old delta file's prose is not a new declaration",
     lambda r: (write(r, ".claude/skills/line-budget.d/old.txt", "w +5\n# why\n"),
                write(r, ".claude/skills/w/references/a.md",
                      "a1\na2\na3\na4\nn1\nn2\nn3\nn4\nn5\n"),
                commit(r, "delta already spent"),
                sh(r, "git", "branch", "-f", "base-ref", "HEAD"),
                write(r, ".claude/skills/line-budget.d/old.txt", "w +5\n# why, reworded\n"),
                commit(r, "reword")),
     0, "0 priced name(s) changed")

# A NEW skill declares itself with its first line-budget.txt row, never a delta.
case("a new skill's ledger row is its declaration",
     lambda r: (write(r, ".claude/skills/z/SKILL.md", "---\nname: z\n---\nz1\nz2\n"),
                write(r, ".claude/skills/line-budget.txt", "w 6\nz 2\nAGENTS.md 3\n"),
                commit(r, "new skill")),
     0, "OK        z: +2 measured, +2 declared")

# Parsed as validate-skills.rb parses it: first two fields, Ruby to_i. A looser
# line the ceiling reads as +1 must not read as no declaration here, or the two
# gates disagree about one file.
case("a delta line with trailing prose is read as the ceiling reads it",
     lambda r: (write(r, ".claude/skills/w/references/a.md", "a1\na2\na3\na4\nn1\n"),
                write(r, ".claude/skills/line-budget.d/b.txt", "w +1 for the new trap\n"),
                commit(r, "loose line")),
     0, "OK        w: +1 measured, +1 declared")

# README.txt is skipped by the ruby parser, so it is not a declaration surface
# here either.
case("README.txt is not a declaration surface",
     lambda r: (write(r, ".claude/skills/w/references/a.md", "a1\na2\na3\na4\nn1\n"),
                write(r, ".claude/skills/line-budget.d/README.txt", "# notes\nw +1\n"),
                commit(r, "declare in the README")),
     1, "MISMATCH  w: +1 measured, +0 declared")

print(f"{cases - failed} passed, {failed} failed")
sys.exit(1 if failed else 0)
