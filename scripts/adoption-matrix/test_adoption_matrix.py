"""Unit tests for adoption_matrix.py.

Run with:
    python3 -m unittest discover -s scripts/adoption-matrix -p 'test_*.py' -v

Each test builds a temporary repo root with its own settings.gradle.kts,
fake demo/<name>/src/{main,test}/kotlin trees and a minimal features table,
so none of them touch the real repository.
"""

from __future__ import annotations

import contextlib
import io
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import adoption_matrix as am  # noqa: E402  (path insert must precede this)


class AdoptionMatrixTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.repo = Path(tempfile.mkdtemp(prefix="adoption-matrix-test-"))
        self.addCleanup(shutil.rmtree, self.repo, ignore_errors=True)

    def write(self, rel_path: str, content: str) -> Path:
        path = self.repo / rel_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def write_settings(self, demo_names: list[str]) -> None:
        lines = [f'include(":demo:{name}")' for name in demo_names]
        self.write("settings.gradle.kts", "\n".join(lines) + ("\n" if lines else ""))

    def write_features(self, patterns: str = "import:com.example.thing.") -> Path:
        return self.write(
            "scripts/adoption-matrix/features.tsv",
            "id\tlabel\theadline\tpatterns\n"
            f"thing\tThing\tyes\t{patterns}\n",
        )


class MainVsTestSourceTest(AdoptionMatrixTestCase):
    """A main-source import flips the cell to USED; the same import under
    src/test does not."""

    def test_main_import_used_test_import_ignored(self) -> None:
        self.write_settings(["foo"])
        features_path = self.write_features()
        self.write(
            "demo/foo/src/main/kotlin/civictech/demo/foo/A.kt",
            "package civictech.demo.foo\n\nimport com.example.thing.Widget\n\nclass A\n",
        )
        self.write(
            "demo/foo/src/test/kotlin/civictech/demo/foo/ATest.kt",
            "package civictech.demo.foo\n\nimport com.example.thing.Widget\n\nclass ATest\n",
        )

        demos = am.discover_demos(self.repo)
        features = am.parse_features(features_path)
        used, evidence = am.build_matrix(self.repo, features, demos)

        self.assertEqual(demos, ["foo"])
        self.assertTrue(used[("thing", "foo")])
        self.assertEqual(
            evidence[("thing", "foo")],
            ["demo/foo/src/main/kotlin/civictech/demo/foo/A.kt"],
        )


class CommentStrippingTest(AdoptionMatrixTestCase):
    """A match only inside // or /* */ comments does not flip the cell."""

    def test_comment_only_match_ignored(self) -> None:
        self.write_settings(["foo"])
        features_path = self.write_features()
        self.write(
            "demo/foo/src/main/kotlin/A.kt",
            "// import com.example.thing.Widget\n"
            "/*\n"
            "import com.example.thing.Widget\n"
            "*/\n"
            "class A\n",
        )

        demos = am.discover_demos(self.repo)
        features = am.parse_features(features_path)
        used, evidence = am.build_matrix(self.repo, features, demos)

        self.assertFalse(used[("thing", "foo")])
        self.assertEqual(evidence[("thing", "foo")], [])


class GraphSpecReplicationTest(AdoptionMatrixTestCase):
    """GraphSpec replication is detected only in uncommented main sources."""

    def test_production_replication_row_uses_graphspec_pattern(self) -> None:
        features = am.parse_features(Path(__file__).with_name("features.tsv"))
        replication = next(feature for feature in features if feature.id == "replication")

        self.assertIn(("regex", r"\breplicated\s*=\s*true\b"), replication.patterns)

    def test_replicated_spawn_main_source_used_test_and_comments_ignored(self) -> None:
        self.write_settings(["foo", "bar"])
        features_path = self.write_features(r"regex:\breplicated\s*=\s*true\b")
        self.write(
            "demo/foo/src/main/kotlin/Graph.kt",
            "val step = SpawnStep(replicated = true)\n"
            "// replicated = true\n"
            "/* replicated = true */\n",
        )
        self.write(
            "demo/foo/src/test/kotlin/GraphTest.kt",
            "val step = SpawnStep(replicated = true)\n",
        )
        self.write(
            "demo/bar/src/main/kotlin/Graph.kt",
            "// SpawnStep(replicated = true)\n"
            "/* SpawnStep(replicated = true) */\n",
        )
        self.write(
            "demo/bar/src/test/kotlin/GraphTest.kt",
            "val step = SpawnStep(replicated = true)\n",
        )

        demos = am.discover_demos(self.repo)
        features = am.parse_features(features_path)
        used, evidence = am.build_matrix(self.repo, features, demos)

        self.assertTrue(used[("thing", "foo")])
        self.assertEqual(evidence[("thing", "foo")], ["demo/foo/src/main/kotlin/Graph.kt"])
        self.assertFalse(used[("thing", "bar")])
        self.assertEqual(evidence[("thing", "bar")], [])


class DemoDiscoveryTest(AdoptionMatrixTestCase):
    """:demo:shell is never a column; the rest are, sorted alphabetically."""

    def test_shell_excluded_and_sorted(self) -> None:
        self.write_settings(["shell", "zeta", "alpha", "middle"])

        demos = am.discover_demos(self.repo)

        self.assertEqual(demos, ["alpha", "middle", "zeta"])


class CheckModeTest(AdoptionMatrixTestCase):
    """--check exits 1 with a diff when stale, 0 when current."""

    def test_check_exit_codes(self) -> None:
        self.write_settings(["foo"])
        self.write_features()
        self.write("demo/foo/src/main/kotlin/A.kt", "class A\n")
        check_path = self.write("doc/FEATURE-STATUS.md", "stale content\n")

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            rc_stale = am.run(["--repo", str(self.repo), "--check", str(check_path)])
        self.assertEqual(rc_stale, 1)
        self.assertIn("stale content", stderr.getvalue())

        rc_out = am.run(["--repo", str(self.repo), "--out", str(check_path)])
        self.assertEqual(rc_out, 0)

        rc_fresh = am.run(["--repo", str(self.repo), "--check", str(check_path)])
        self.assertEqual(rc_fresh, 0)


class MalformedInputTest(AdoptionMatrixTestCase):
    """An unknown pattern kind, or zero discovered demo modules, exits 2."""

    def test_unknown_pattern_kind_exits_2(self) -> None:
        self.write_settings(["foo"])
        bad_features = self.write(
            "scripts/adoption-matrix/features.tsv",
            "id\tlabel\theadline\tpatterns\n"
            "thing\tThing\tyes\tbogus:whatever\n",
        )

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            rc = am.run(["--repo", str(self.repo), "--features", str(bad_features)])

        self.assertEqual(rc, 2)

    def test_empty_demo_set_exits_2(self) -> None:
        self.write_settings([])
        features_path = self.write_features()

        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            rc = am.run(["--repo", str(self.repo), "--features", str(features_path)])

        self.assertEqual(rc, 2)


if __name__ == "__main__":
    unittest.main()
