#!/usr/bin/env python3
"""Derive the feature x demo adoption matrix from demo main-source imports.

Usage:
    adoption_matrix.py [--repo PATH] [--features PATH] [--out PATH | --check PATH]

Reads the checked-in feature/pattern table (``scripts/adoption-matrix/features.tsv``
by default) and scans ``demo/<name>/src/main/**/*.kt`` for every
``include(":demo:<name>")`` of ``settings.gradle.kts`` except ``:demo:shell``
(the shared HTTP/SSE shell, not an application per AGENTS.md's repository
map). For each feature row, a demo's cell is ``USED`` when any of the row's
patterns matches a comment-stripped line of one of that demo's main-source
files, else ``-``. Test source sets (``src/test``) are never scanned, for
any feature.

Two pattern kinds appear in ``features.tsv``'s ``patterns`` column,
``|``-separated:

    import:<prefix>   matches a comment-stripped line "import <prefix>...";
                      a trailing '.' means "this package or below", no
                      trailing dot means the exact symbol or a longer name
                      starting with it (a plain string-prefix match on the
                      imported name).
    regex:<python re> ``re.search`` against each comment-stripped line.

Comment stripping removes ``/* */`` block comments then ``//`` line
comments (same technique and order as
``kernel/src/test/kotlin/civictech/cell/architecture/DemoBypassRatchetTest.kt``'s
``stripComments``, itself borrowed from ``demo/social``'s
``ModuleDependencyTest``). String literals containing ``//`` or ``/*`` are
not excluded from stripping; that imprecision is accepted, not fixed here.

An import proves presence, not quality of use: a demo that bypasses the
platform concept it names (report 06's "B" rows) reads the same as one that
never touches it, both ``-``. Transitive use reached only through ``:wire``
wiring or DSL configuration, without a matching import/regex in the demo's
own main source, is not counted. Mobility and membranes have no
import-detectable surface in today's demos (or are postponed; see the
umbrella epic's non-goals) and are not rows in the table.

With no ``--out``/``--check``, prints the generated Markdown to stdout.
``--out PATH`` writes it to PATH. ``--check PATH`` regenerates and compares
byte-for-byte with PATH: exit 0 on a match, exit 1 with a unified diff on
stderr otherwise. Exit 2 on a malformed features table, an unknown pattern
kind, or zero discovered demo modules — so an empty matrix can never pass
as a green one.

This is what generates doc/FEATURE-STATUS.md; regenerate it with:
    python3 scripts/adoption-matrix/adoption_matrix.py --out doc/FEATURE-STATUS.md
"""

from __future__ import annotations

import argparse
import difflib
import re
import sys
from pathlib import Path

# civictech demo/shell is the shared HTTP/SSE shell (DemoShell), "not an
# application" (AGENTS.md repository map), and excluded from report 06 too.
EXCLUDED_DEMOS = {"shell"}

INCLUDE_RE = re.compile(r'include\(":demo:([A-Za-z0-9_-]+)"\)')

BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")

TABLE_HEADER = ("id", "label", "headline", "patterns")

OLD_SURVEY_SHA = "1de7dfd5"


class FeatureTableError(Exception):
    """Raised for a malformed features.tsv."""


class NoDemoModulesError(Exception):
    """Raised when settings.gradle.kts yields no demo modules."""


class Feature:
    __slots__ = ("id", "label", "headline", "patterns")

    def __init__(self, id_: str, label: str, headline: bool, patterns: list[tuple[str, str]]):
        self.id = id_
        self.label = label
        self.headline = headline
        self.patterns = patterns


def strip_comments(text: str) -> str:
    """Strip ``/* */`` block comments then ``//`` line comments.

    See the module docstring for the precedent this follows and the
    string-literal imprecision it accepts.
    """
    return LINE_COMMENT_RE.sub("", BLOCK_COMMENT_RE.sub("", text))


def discover_demos(repo_root: Path) -> list[str]:
    """Demo module names from settings.gradle.kts, ``shell`` excluded, sorted."""
    settings = repo_root / "settings.gradle.kts"
    text = settings.read_text(encoding="utf-8")
    names = {m.group(1) for m in INCLUDE_RE.finditer(text)} - EXCLUDED_DEMOS
    if not names:
        raise NoDemoModulesError(
            f"no demo modules found in {settings} (excluding {sorted(EXCLUDED_DEMOS)})"
        )
    return sorted(names)


def parse_features(path: Path) -> list[Feature]:
    """Parse the '#'-commented, tab-separated features table at ``path``."""
    try:
        raw_lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise FeatureTableError(f"{path}: {exc}") from exc

    rows: list[tuple[int, str]] = []
    for lineno, raw in enumerate(raw_lines, start=1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        rows.append((lineno, raw))
    if not rows:
        raise FeatureTableError(f"{path}: no data rows")

    header_lineno, header_line = rows[0]
    header = tuple(header_line.split("\t"))
    if header != TABLE_HEADER:
        raise FeatureTableError(
            f"{path}:{header_lineno}: expected header {TABLE_HEADER!r}, got {header!r}"
        )

    features: list[Feature] = []
    seen_ids: set[str] = set()
    for lineno, line in rows[1:]:
        fields = line.split("\t")
        if len(fields) != 4:
            raise FeatureTableError(
                f"{path}:{lineno}: expected 4 tab-separated fields, got {len(fields)}: {line!r}"
            )
        id_, label, headline_raw, patterns_raw = fields
        if not id_:
            raise FeatureTableError(f"{path}:{lineno}: empty id")
        if id_ in seen_ids:
            raise FeatureTableError(f"{path}:{lineno}: duplicate id {id_!r}")
        seen_ids.add(id_)
        if headline_raw not in ("yes", "no"):
            raise FeatureTableError(
                f"{path}:{lineno}: headline must be 'yes' or 'no', got {headline_raw!r}"
            )
        patterns: list[tuple[str, str]] = []
        for token in patterns_raw.split("|"):
            if token.startswith("import:"):
                patterns.append(("import", token[len("import:"):]))
            elif token.startswith("regex:"):
                patterns.append(("regex", token[len("regex:"):]))
            else:
                raise FeatureTableError(
                    f"{path}:{lineno}: unknown pattern kind in {token!r} "
                    "(expected an 'import:' or 'regex:' prefix)"
                )
        if not patterns:
            raise FeatureTableError(f"{path}:{lineno}: no patterns")
        features.append(Feature(id_, label, headline_raw == "yes", patterns))
    return features


def main_files(repo_root: Path, demo: str) -> list[Path]:
    """Sorted ``*.kt`` files under ``demo/<demo>/src/main`` (never ``src/test``)."""
    root = repo_root / "demo" / demo / "src" / "main"
    if not root.is_dir():
        return []
    return sorted(root.rglob("*.kt"))


def pattern_matches_line(kind: str, value: str, line: str) -> bool:
    if kind == "import":
        stripped = line.strip()
        if not stripped.startswith("import "):
            return False
        target = stripped[len("import "):].strip()
        return target.startswith(value)
    if kind == "regex":
        return re.search(value, line) is not None
    raise FeatureTableError(f"unknown pattern kind {kind!r}")


def build_matrix(repo_root: Path, features: list[Feature], demos: list[str]):
    """Returns ``(used, evidence)``.

    ``used[(feature.id, demo)]`` is a bool; ``evidence[(feature.id, demo)]``
    is the sorted list of repo-relative posix paths that matched.
    """
    files_by_demo = {demo: main_files(repo_root, demo) for demo in demos}
    stripped_by_file: dict[Path, str] = {}
    for files in files_by_demo.values():
        for f in files:
            if f not in stripped_by_file:
                stripped_by_file[f] = strip_comments(f.read_text(encoding="utf-8"))

    used: dict[tuple[str, str], bool] = {}
    evidence: dict[tuple[str, str], list[str]] = {}
    for feature in features:
        for demo in demos:
            matched_paths = []
            for f in files_by_demo[demo]:
                lines = stripped_by_file[f].splitlines()
                if any(
                    pattern_matches_line(kind, value, line)
                    for kind, value in feature.patterns
                    for line in lines
                ):
                    matched_paths.append(f.relative_to(repo_root).as_posix())
            key = (feature.id, demo)
            used[key] = bool(matched_paths)
            evidence[key] = sorted(matched_paths)
    return used, evidence


def render_table(features: list[Feature], demos: list[str], used, title: str, summary_row: bool) -> list[str]:
    if not features:
        return []
    header = ["Feature"] + demos + ["demos"]
    out = [f"### {title}", "", "| " + " | ".join(header) + " |", "|" + "|".join(["---"] * len(header)) + "|"]
    for feature in features:
        row_used = [used[(feature.id, demo)] for demo in demos]
        cells = ["USED" if u else "-" for u in row_used]
        count = sum(1 for u in row_used if u)
        out.append("| " + " | ".join([feature.label] + cells + [str(count)]) + " |")
    if summary_row:
        counts = [str(sum(1 for feature in features if used[(feature.id, demo)])) for demo in demos]
        out.append("| " + " | ".join(["**headline count**"] + counts + [""]) + " |")
    out.append("")
    return out


def render_evidence(features: list[Feature], demos: list[str], used, evidence) -> list[str]:
    lines = ["## Evidence", ""]
    any_evidence = False
    for feature in features:
        for demo in demos:
            key = (feature.id, demo)
            if used[key]:
                any_evidence = True
                lines.append(f"- {feature.id} / {demo}: {', '.join(evidence[key])}")
    if not any_evidence:
        lines.append("- (no USED cells)")
    lines.append("")
    return lines


def generate_markdown(repo_root: Path, features_path: Path, features: list[Feature], demos: list[str], used, evidence) -> str:
    try:
        features_rel = features_path.relative_to(repo_root).as_posix()
    except ValueError:
        features_rel = str(features_path)

    lines = [
        "# ComputeNet — Feature x Demo Adoption Matrix",
        "",
        "> **GENERATED — do not hand-edit.** Regenerate with:",
        ">",
        "> ```",
        "> python3 scripts/adoption-matrix/adoption_matrix.py --out doc/FEATURE-STATUS.md",
        "> ```",
        ">",
        f"> **Method**: for every `include(\":demo:<name>\")` of `settings.gradle.kts`"
        f" except `:demo:shell` (the shared HTTP/SSE shell, not an application), scan"
        f" `demo/<name>/src/main/**/*.kt` — never `src/test` — for the import/usage"
        f" patterns in `{features_rel}`, after stripping `//` line comments and"
        f" `/* */` block comments. A cell is `USED` when any pattern of that row"
        f" matches a comment-stripped line of any main-source file of that demo.",
        ">",
        "> **Imprecision, by design**: an import proves presence, not quality of"
        " use — a demo that bypasses the platform concept it names (report 06's"
        " `B` rows) reads the same as one that never touches it, `-`. String"
        " literals containing `//` or `/*` are not excluded from comment"
        " stripping. Transitive use reached only through `:wire` wiring or DSL"
        " configuration, without a matching import/regex in the demo's own main"
        " source, is not counted.",
        ">",
        "> **Not measured**: mobility and membranes have no import-detectable"
        " surface in today's demos (or are postponed by the umbrella epic's"
        " non-goals) and are not rows here. Test source sets (`src/test`) are"
        " never scanned, for any feature.",
        ">",
        f"> The 2026-07-25 hand-written survey this file replaced is in git"
        f" history: `git show {OLD_SURVEY_SHA}:doc/FEATURE-STATUS.md`.",
        "",
    ]

    headline_features = [f for f in features if f.headline]
    extra_features = [f for f in features if not f.headline]

    lines += render_table(headline_features, demos, used, "Headline features", summary_row=True)
    lines += render_table(
        extra_features, demos, used,
        "Additional features (not part of the umbrella epic's acceptance)",
        summary_row=False,
    )

    lines.append("## Headline features used by no demo")
    lines.append("")
    unused = [f for f in headline_features if not any(used[(f.id, demo)] for demo in demos)]
    if unused:
        lines.extend(f"- {f.label} (`{f.id}`)" for f in unused)
    else:
        lines.append("- (none — every headline feature is used by at least one demo)")
    lines.append("")

    lines += render_evidence(features, demos, used, evidence)

    return "\n".join(lines).rstrip("\n") + "\n"


def build_arg_parser() -> argparse.ArgumentParser:
    # Script lives at <repo>/scripts/adoption-matrix/adoption_matrix.py, two
    # directories below the repo root.
    default_repo = Path(__file__).resolve().parent.parent.parent
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--repo", type=Path, default=default_repo, help="repo root (default: %(default)s)")
    parser.add_argument(
        "--features", type=Path, default=None,
        help="features table path (default: <repo>/scripts/adoption-matrix/features.tsv)",
    )
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--out", type=Path, help="write the generated Markdown to this path")
    group.add_argument(
        "--check", type=Path,
        help="compare the generated Markdown against this path; exit 1 with a diff on mismatch",
    )
    return parser


def run(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    repo_root = args.repo.resolve()
    features_path = (
        args.features.resolve() if args.features
        else (repo_root / "scripts" / "adoption-matrix" / "features.tsv")
    )

    try:
        demos = discover_demos(repo_root)
        features = parse_features(features_path)
        used, evidence = build_matrix(repo_root, features, demos)
        markdown = generate_markdown(repo_root, features_path, features, demos, used, evidence)
    except (FeatureTableError, NoDemoModulesError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    if args.check is not None:
        try:
            existing = args.check.read_text(encoding="utf-8")
        except OSError:
            existing = ""
        if existing == markdown:
            return 0
        diff = difflib.unified_diff(
            existing.splitlines(keepends=True),
            markdown.splitlines(keepends=True),
            fromfile=str(args.check),
            tofile="<regenerated>",
        )
        sys.stderr.writelines(diff)
        return 1

    if args.out is not None:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(markdown, encoding="utf-8")
        return 0

    sys.stdout.write(markdown)
    return 0


def main(argv: list[str] | None = None) -> int:
    return run(argv)


if __name__ == "__main__":
    sys.exit(main())
