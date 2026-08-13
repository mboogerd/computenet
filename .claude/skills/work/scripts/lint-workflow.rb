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

  steps = 0
  bad = 0
  (doc['jobs'] || {}).each do |job, spec|
    (spec['steps'] || []).each do |st|
      next unless st['run']
      steps += 1
      IO.popen(['bash', '-n'], 'w') { |io| io.write(st['run']) }
      next if $?.success?
      warn "#{path}: bash syntax error in job '#{job}', step '#{st['name'] || '(unnamed)'}'"
      bad += 1
    end
  end

  puts "#{path}: YAML parsed, #{steps} run-blocks checked with bash -n, #{bad} bad"
  failures += bad
end

exit(failures.zero? ? 0 : 1)
