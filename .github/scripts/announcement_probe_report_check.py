#!/usr/bin/env python3
"""Check announcement_probe_report.py's classifier against synthetic JUnit XML.

Run it: `python3 .github/scripts/announcement_probe_report_check.py` (no
arguments, no dependencies, no test framework — the script under check is plain
Python and stdlib-only, and so is this).

WHY IT EXISTS. The classifier is the only thing standing between an
announcement-probe run and the record on computenet-dqy.40, and it is the kind
of code whose bugs are invisible until an occurrence is already misfiled.
computenet-dqy.54 was exactly that: "no failures -> clean; all timeouts ->
incomplete; ANYTHING ELSE -> loss" reported an OutOfMemoryError as an
announcement loss. Each case below is a shape of JUnit XML the probe job can
really produce, and asserts the outcome, the numerator and the denominator the
ledger would publish for it.

It also cross-checks .github/workflows/announcement-probe.yml: every outcome
this script can emit must have a verdict arm in the Conclude step, or the job
would fall through to "did not classify this arm".
"""

import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
# Overridable so the cases can be run against an OLD copy of the report script
# (e.g. `git show HEAD~1:...`) to prove they actually catch the regression they
# describe, rather than passing by construction.
REPORT = os.environ.get("PROBE_REPORT_SCRIPT") or os.path.join(
    HERE, "announcement_probe_report.py"
)
WORKFLOW = os.path.join(HERE, os.pardir, "workflows", "announcement-probe.yml")

CLASS = "WsAnnouncementStressTest"
FQCN = "civictech.wire.WsAnnouncementStressTest"

# Verbatim shapes, kept close to what the real runs produce.
LOSS_REPORT = (
    "announcement path: 2 failure(s) in 8000 awaits over 4000 iterations\n"
    "arrival latency ms: p50=1 p99=9 max=2841\n"
    "--- iteration 118 (catch-up): never arrived within 5000ms\n"
    "server localRefs=[r1] client remoteRefs=[]"
)
BURST_REPORT = (
    "catch-up burst: 1 failed iteration(s) in 6000 iterations (240000 refs announced)\n"
    "released inside the register-then-sweep window: 12 refs"
)
TIMEOUT_MESSAGE = (
    "java.util.concurrent.TimeoutException: testStressAnnouncement() timed out "
    "after 5 minutes"
)
OOM_MESSAGE = "Java heap space"


def testcase(name, problems=(), skipped=False, classname=FQCN, time="159.639"):
    body = ""
    if skipped:
        body += "    <skipped/>\n"
    for tag, ptype, message, text in problems:
        body += '    <%s message="%s" type="%s">%s</%s>\n' % (
            tag,
            xml_attr(message),
            ptype,
            xml_text(text),
            tag,
        )
    return '  <testcase name="%s" classname="%s" time="%s">\n%s  </testcase>\n' % (
        name,
        classname,
        time,
        body,
    )


def xml_attr(value):
    return (
        value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace('"', "&quot;")
        .replace("\n", "&#10;")
    )


def xml_text(value):
    return value.replace("&", "&amp;").replace("<", "&lt;")


def suite(cases):
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<testsuite name="%s" tests="1">\n%s</testsuite>\n' % (FQCN, "".join(cases))
    )


def run_case(xml, expected_units="8000"):
    """Run the report script over one synthetic results dir.

    Returns (exit code, stdout, published env dict).
    """
    with tempfile.TemporaryDirectory() as tmp:
        results = os.path.join(tmp, "results")
        os.makedirs(results)
        if xml is not None:
            with open(os.path.join(results, "TEST-%s.xml" % FQCN), "w") as handle:
                handle.write(xml)
        env_file = os.path.join(tmp, "github_env")
        open(env_file, "w").close()
        env = dict(os.environ)
        env.update(
            {
                "GITHUB_ENV": env_file,
                "PROBE_OUTCOME_KEY": "STRESS_OUTCOME",
                "PROBE_LOSSES_KEY": "STRESS_LOSSES",
                "PROBE_UNITS_KEY": "STRESS_UNITS",
                "PROBE_EXPECTED_UNITS": expected_units,
            }
        )
        env.pop("GITHUB_STEP_SUMMARY", None)
        proc = subprocess.run(
            [sys.executable, REPORT, results, "check", CLASS],
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            universal_newlines=True,
        )
        published = {}
        with open(env_file) as handle:
            for line in handle:
                if "=" in line:
                    key, value = line.rstrip("\n").split("=", 1)
                    published[key] = value
        return proc.returncode, proc.stdout, published


FAILED = []


def expect(name, xml, outcome, losses, units, code, must_say=(), must_not_say=()):
    rc, out, published = run_case(xml)
    problems = []
    if published.get("STRESS_OUTCOME") != outcome:
        problems.append(
            "outcome %r, expected %r" % (published.get("STRESS_OUTCOME"), outcome)
        )
    if published.get("STRESS_LOSSES") != losses:
        problems.append(
            "losses %r, expected %r" % (published.get("STRESS_LOSSES"), losses)
        )
    if published.get("STRESS_UNITS") != units:
        problems.append("units %r, expected %r" % (published.get("STRESS_UNITS"), units))
    if rc != code:
        problems.append("exit %d, expected %d" % (rc, code))
    for needle in must_say:
        if needle not in out:
            problems.append("output does not contain %r" % needle)
    for needle in must_not_say:
        if needle in out:
            problems.append("output wrongly contains %r" % needle)
    if problems:
        FAILED.append(name)
        print("FAIL  %s" % name)
        for problem in problems:
            print("        %s" % problem)
        print("      ---- output ----")
        for line in out.splitlines():
            print("      | %s" % line)
    else:
        print(
            "ok    %s  ->  %s (losses=%s of %s, exit %d)"
            % (name, outcome, losses, units, rc)
        )


def main():
    # 1. Clean: an executed testcase with no problem record. The probe prints
    #    nothing on success, so the denominator is the dispatched size.
    expect(
        "clean",
        suite([testcase("stress announcement path")]),
        outcome="clean",
        losses="0",
        units="8000",
        code=0,
        must_say=["PASSED - no announcement was lost"],
        must_not_say=["LOST -", "UNRECOGNISED"],
    )

    # 2. Timeout only: the dispatched size did not fit the build's 5-minute
    #    per-test timeout (computenet-dqy.12/.50). No sample, no counts.
    expect(
        "timeout-only",
        suite(
            [
                testcase(
                    "stress announcement path",
                    [("failure", "java.util.concurrent.TimeoutException",
                      TIMEOUT_MESSAGE, TIMEOUT_MESSAGE + "\n\tat java.base/...")],
                )
            ]
        ),
        outcome="incomplete",
        losses="unknown",
        units="unknown",
        code=0,
        must_say=["INCOMPLETE", "NOT an announcement loss"],
        must_not_say=["LOST -"],
    )

    # 3. A real loss: an AssertionFailedError carrying the probe's own report
    #    line. This is the occurrence, and its counts come off that line, NOT
    #    off PROBE_EXPECTED_UNITS.
    expect(
        "loss-with-report-line",
        suite(
            [
                testcase(
                    "stress announcement path",
                    [("failure", "org.opentest4j.AssertionFailedError",
                      LOSS_REPORT, LOSS_REPORT + "\n\tat civictech.wire...")],
                )
            ]
        ),
        outcome="loss",
        losses="2",
        units="8000",
        code=0,
        must_say=["LOST - an announcement did not arrive"],
        must_not_say=["UNRECOGNISED"],
    )

    # 3b. The burst probe's own report line is a different sentence and must be
    #     recognised too, with ITS units (iterations, not awaits).
    expect(
        "loss-burst-report-line",
        suite(
            [
                testcase(
                    "catch-up burst",
                    [("failure", "org.opentest4j.AssertionFailedError",
                      BURST_REPORT, BURST_REPORT)],
                )
            ]
        ),
        outcome="loss",
        losses="1",
        units="6000",
        code=0,
        must_say=["LOST - an announcement did not arrive"],
    )

    # 4. Loss alongside a timeout: still a loss. The loss is the real evidence
    #    and must not be diluted by the uninformative record next to it.
    expect(
        "loss-plus-timeout",
        suite(
            [
                testcase(
                    "stress announcement path",
                    [
                        ("failure", "org.opentest4j.AssertionFailedError",
                         LOSS_REPORT, LOSS_REPORT),
                        ("failure", "java.util.concurrent.TimeoutException",
                         TIMEOUT_MESSAGE, TIMEOUT_MESSAGE),
                    ],
                )
            ]
        ),
        outcome="loss",
        losses="2",
        units="8000",
        code=0,
        must_say=["LOST - an announcement did not arrive", "INCOMPLETE"],
    )

    # 5. THE computenet-dqy.54 CASE. An OutOfMemoryError is neither a timeout
    #    nor a probe report line, so it is not an occurrence — and, crucially,
    #    it is also not a pass: it contributes no numerator and no denominator.
    expect(
        "non-probe-error-oom",
        suite(
            [
                testcase(
                    "stress announcement path",
                    [("error", "java.lang.OutOfMemoryError", OOM_MESSAGE,
                      "java.lang.OutOfMemoryError: Java heap space\n\tat java.base/...")],
                )
            ]
        ),
        outcome="unrecognised",
        losses="unknown",
        units="unknown",
        code=0,
        must_say=["UNRECOGNISED FAILURE", "java.lang.OutOfMemoryError"],
        must_not_say=["LOST -"],
    )

    # 5b. An unrecognised failure alongside a timeout is still unrecognised —
    #     the less-explained record is the one a reader must act on.
    expect(
        "non-probe-error-plus-timeout",
        suite(
            [
                testcase(
                    "stress announcement path",
                    [
                        ("failure", "java.util.concurrent.TimeoutException",
                         TIMEOUT_MESSAGE, TIMEOUT_MESSAGE),
                        ("error", "java.lang.OutOfMemoryError", OOM_MESSAGE, OOM_MESSAGE),
                    ],
                )
            ]
        ),
        outcome="unrecognised",
        losses="unknown",
        units="unknown",
        code=0,
        must_say=["UNRECOGNISED FAILURE", "INCOMPLETE"],
        must_not_say=["LOST -"],
    )

    # 6. Vacuous, three ways: no XML at all, XML with no matching class, and a
    #    skipped testcase. All three look like a large clean sample unless the
    #    script refuses them, so each must exit non-zero.
    expect(
        "vacuous-no-xml",
        None,
        outcome="vacuous",
        losses="unknown",
        units="unknown",
        code=1,
        must_say=["no JUnit XML"],
    )
    expect(
        "vacuous-other-class",
        suite([testcase("something else", classname="civictech.wire.WsHelloTest")]),
        outcome="vacuous",
        losses="unknown",
        units="unknown",
        code=1,
        must_say=["produced no executed testcase"],
    )
    expect(
        "vacuous-skipped",
        suite([testcase("stress announcement path", skipped=True)]),
        outcome="vacuous",
        losses="unknown",
        units="unknown",
        code=1,
        must_say=["SKIPPED", "produced no executed testcase"],
    )

    check_workflow_handles_every_outcome()

    if FAILED:
        print("\n%d case(s) FAILED: %s" % (len(FAILED), ", ".join(FAILED)))
        return 1
    print("\nall cases pass")
    return 0


def check_workflow_handles_every_outcome():
    """Every outcome the script publishes needs a verdict arm in Conclude.

    Without one the job falls through to "the report step did not classify this
    arm", which is red but says nothing useful. Parsed with ruby's YAML because
    this host has no pyyaml and pip3 is PEP-668 blocked; skipped rather than
    failed where ruby is absent, since this is a cross-check and not the
    subject.
    """
    outcomes = ("clean", "loss", "incomplete", "unrecognised", "vacuous")
    script = (
        "require 'yaml'; d = YAML.load(File.read(ARGV[0])); "
        "d['jobs'].each_value { |j| j['steps'].each { |s| "
        "print s['run'].to_s if s['name'] == 'Conclude' } }"
    )
    try:
        proc = subprocess.run(
            ["ruby", "-e", script, WORKFLOW],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            universal_newlines=True,
        )
    except OSError:
        print("skip  workflow cross-check (no ruby on this host)")
        return
    if proc.returncode != 0:
        FAILED.append("workflow-yaml-parse")
        print("FAIL  workflow-yaml-parse: %s" % proc.stderr.strip())
        return
    block = proc.stdout
    if not block.strip():
        FAILED.append("workflow-conclude-missing")
        print("FAIL  workflow-conclude-missing: no 'Conclude' step with a run block")
        return
    missing = [o for o in outcomes if not re.search(r"^\s*%s\)" % o, block, re.M)]
    if missing:
        FAILED.append("workflow-verdict-arms")
        print("FAIL  workflow-verdict-arms: no case arm for %s" % ", ".join(missing))
    else:
        print("ok    workflow Conclude has a verdict arm for every outcome")
    # The unrecognised arm must redden the run and must not assert a loss.
    arm = re.search(r"^\s*unrecognised\)(.*?);;", block, re.S | re.M)
    if arm:
        text = arm.group(0)
        if "rc=1" not in text or "::error::" not in text:
            FAILED.append("workflow-unrecognised-red")
            print("FAIL  workflow-unrecognised-red: the arm does not redden the run")
        elif "ANNOUNCEMENT LOST" in text:
            FAILED.append("workflow-unrecognised-claims-loss")
            print("FAIL  workflow-unrecognised-claims-loss: the arm asserts a loss")
        else:
            print("ok    workflow unrecognised arm reddens without asserting a loss")


if __name__ == "__main__":
    sys.exit(main())
