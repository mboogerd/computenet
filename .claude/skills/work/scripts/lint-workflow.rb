#!/usr/bin/env ruby
# Validate a GitHub Actions workflow on THIS host (computenet-zu15).
#
# There is no actionlint here and `pip3 install pyyaml` is PEP-668 blocked, so
# a reviewer of a workflow change was hand-rolling this per review — and one
# that skipped it was certifying YAML nobody had parsed.
#
# Two things it checks, which is what a syntax-level review actually needs:
#   1. the file is parseable YAML;
#   2. every `run:` block is syntactically valid bash (`bash -n`).
#
# It walks BOTH shapes: a workflow's `jobs.*.steps[]` and a composite action's
# `runs.steps[]`. It used to walk only the first, so on a
# `.github/actions/*/action.yml` it printed "0 run-blocks checked" and exited 0
# — a vacuous lint that reads as a clean one. That bit the review of
# computenet-q4qt (PR #570), whose whole change was factoring a CI safety net
# — an assertion that reddens a lane when a required e2e class produced no
# JUnit XML — out of two inline copies and into a composite action, i.e. the
# script that moved into the unlinted file WAS the assertion (computenet-f38z).
# A silently disarmed safety net leaves every required check green.
#
# A file from which zero run-blocks were extracted now says NOTHING CHECKED
# rather than "0 bad", because the two are indistinguishable in a report and
# only one of them is evidence.
#
# It does NOT know the Actions schema — a misspelled key or a bad `uses:` ref
# passes. For that, prefer actionlint if it ever lands on this host.
#
# Usage: ruby .claude/skills/work/scripts/lint-workflow.rb .github/workflows/*.yml
# Exit:  0 = all good, 1 = a parse error or a bad run-block.
require 'yaml'

# NOTE: YAML.unsafe_load_file does not exist in this host Ruby's Psych — it
# suggests safe_load. YAML.load(File.read(...)) is the form that works.
failures = 0

ARGV.each do |path|
  begin
    doc = YAML.load(File.read(path))
  rescue => e
    warn "#{path}: YAML PARSE ERROR: #{e.message}"
    failures += 1
    next
  end

  # An empty file parses to `false` and a top-level list to an Array; both used
  # to crash with a Ruby backtrace on the `doc['jobs']` below (review, PR #116).
  unless doc.is_a?(Hash)
    warn "#{path}: not a YAML mapping (#{doc.class}) — no jobs to check"
    failures += 1
    next
  end

  # [owner, steps] pairs: a workflow's jobs, then a composite action's `runs`.
  containers = []
  jobs = doc['jobs']
  (jobs.is_a?(Hash) ? jobs : {}).each do |job, spec|
    containers << ["job '#{job}'", spec['steps']] if spec.is_a?(Hash)
  end
  runs = doc['runs']
  containers << ["composite action", runs['steps']] if runs.is_a?(Hash)

  steps = 0
  bad = 0
  containers.each do |owner, steps_list|
    (steps_list.is_a?(Array) ? steps_list : []).each do |st|
      next unless st.is_a?(Hash) && st['run']
      steps += 1
      IO.popen(['bash', '-n'], 'w') { |io| io.write(st['run']) }
      next if $?.success?
      warn "#{path}: bash syntax error in #{owner}, step '#{st['name'] || '(unnamed)'}'"
      bad += 1
    end
  end

  if steps.zero?
    puts "#{path}: YAML parsed, NOTHING CHECKED — 0 run-blocks found (this file has no bash to lint; do not cite it as coverage)"
  else
    puts "#{path}: YAML parsed, #{steps} run-blocks checked with bash -n, #{bad} bad"
  end
  failures += bad
end

exit(failures.zero? ? 0 : 1)
