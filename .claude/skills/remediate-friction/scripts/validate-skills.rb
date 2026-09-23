#!/usr/bin/env ruby
# Check every skill under .claude/skills/ against Anthropic's skill-creator
# structural criteria (computenet-wpvy.38).
#
# WHY THIS EXISTS RATHER THAN THE UPSTREAM SCRIPT. skill-creator ships
# scripts/quick_validate.py, whose criteria these are, but it imports pyyaml
# and `pip3 install` is PEP-668 blocked on this host — the same constraint
# recorded in computenet-zu15, and the same reason
# .claude/skills/work/scripts/lint-workflow.rb is ruby. The criteria are
# transcribed, not invented; see that script for the source of each.
#
# WHAT IT IS NOT. skill-creator's other tier — run_eval.py, run_loop.py and
# the grader/analyzer/comparator agents — spawns with-skill and baseline runs
# over authored test cases and takes hours. That is a cadence or on-demand
# tool, never a per-change gate, and this script is not it.
#
# THE CAPS, replacing the 2026-08 line-budget ratchet (2026-09-14). The
# ratchet priced growth with a justification file, and agents write
# justifications without friction: /work grew 2,900 lines under it. A cap is a
# ceiling that no paragraph raises. Over a cap means rewrite, not explain.
#
# Cited script paths must resolve: a wrong path sent agents to conclude a
# script was unlanded and hand-roll a substitute.
#
# Usage: ruby .claude/skills/remediate-friction/scripts/validate-skills.rb [dir]
# Exit:  0 all pass, 1 any failure.
require 'yaml'

ALLOWED = %w[name description license allowed-tools metadata compatibility].freeze
# Real Claude Code frontmatter that skill-creator's allowlist predates. Kept
# separate from ALLOWED so the divergence stays visible rather than silently
# blessed: `disable-model-invocation` is used by shipped plugins in
# ~/.claude/plugins/marketplaces/, so failing it would mean deleting a working
# property to please a linter.
TOLERATED = %w[disable-model-invocation].freeze

root = ARGV[0] || '.claude/skills'
# Cited paths are written repo-root-relative, which is two levels above the
# skills directory (.claude/skills -> .claude -> repo root).
repo_root = File.dirname(File.dirname(root))
files = Dir.glob(File.join(root, '*', 'SKILL.md')).sort
abort "no SKILL.md found under #{root}" if files.empty?

# A skill directory with no SKILL.md is a broken skill, not an absence.
missing = Dir.glob(File.join(root, '*')).select { |d| File.directory?(d) } -
          files.map { |f| File.dirname(f) }

# Whole-file line caps. Deliberately hard: raising one is a reviewed edit to
# this file, never a side effect of the change that needed the room.
SKILL_CAP = 600
SKILL_CAPS = { 'remediate-friction' => 150 }.freeze
REFERENCE_CAP = 300
AGENTS_CAP = 700

# Any backtick-free run of path characters ending in a script extension and
# containing a scripts/ segment. Deliberately broad: a citation is a citation
# whether or not it is fenced, and the check is only ever "does this resolve".
CITED_SCRIPT = %r{[A-Za-z0-9_./-]*scripts/[A-Za-z0-9_./-]+\.(?:py|sh|rb)}.freeze

failures = 0
files.each do |f|
  skill = File.basename(File.dirname(f))
  errs = []
  warns = []
  body = File.read(f, encoding: 'UTF-8')

  fm = body[/\A---\n(.*?)\n---/m, 1]
  if fm.nil?
    errs << 'no YAML frontmatter'
  else
    begin
      y = YAML.load(fm)
      unless y.is_a?(Hash)
        errs << "frontmatter is not a mapping (got #{y.class})"
        y = {}
      end

      unknown = y.keys - ALLOWED - TOLERATED
      errs << "unexpected key(s): #{unknown.join(', ')}" unless unknown.empty?
      (y.keys & TOLERATED).each do |k|
        warns << "#{k}: valid in Claude Code, outside skill-creator's allowlist"
      end

      # key?() alone is not enough: `name:` with no value parses to nil, and
      # the per-field checks below all skip a blank string — so a skill with
      # an empty name and description passed clean. Require non-blank.
      %w[name description].each do |k|
        errs << "missing '#{k}'" unless y.key?(k)
        errs << "'#{k}' is empty" if y.key?(k) && y[k].to_s.strip.empty?
      end

      name = y['name'].to_s
      unless name.empty?
        errs << "name '#{name}' is not kebab-case" unless name =~ /\A[a-z0-9-]+\z/
        errs << "name starts/ends with '-' or has '--'" if name =~ /\A-|-\z|--/
        errs << "name is #{name.length} chars (max 64)" if name.length > 64
      end

      desc = y['description'].to_s
      unless desc.empty?
        errs << 'description contains angle brackets' if desc =~ /[<>]/
        errs << "description is #{desc.length} chars (max 1024)" if desc.length > 1024
      end
    rescue StandardError => e
      errs << "invalid YAML: #{e.message}"
    end
  end

  cap = SKILL_CAPS.fetch(skill, SKILL_CAP)
  n = body.lines.length
  errs << "SKILL.md is #{n} lines, over its cap of #{cap}" if n > cap
  Dir.glob(File.join(File.dirname(f), 'references', '*.md')).sort.each do |r|
    n = File.readlines(r, encoding: 'UTF-8').length
    errs << "references/#{File.basename(r)} is #{n} lines, over its cap of #{REFERENCE_CAP}" if n > REFERENCE_CAP
  end

  # Cited script paths must resolve from the repo root, which is where an
  # agent's shell sits. A path is reported once per file that cites it, with
  # the line number, so the fix is a single edit rather than a hunt.
  ([f] + Dir.glob(File.join(File.dirname(f), 'references', '*.md')).sort).each do |src|
    rel = src.sub(%r{\A#{Regexp.escape(File.dirname(File.dirname(f)))}/}, '')
    File.readlines(src, encoding: 'UTF-8').each_with_index do |line, i|
      line.scan(CITED_SCRIPT).uniq.each do |path|
        next if File.exist?(File.join(repo_root, path))

        errs << "#{rel}:#{i + 1} cites #{path}, which does not exist"
      end
    end
  end

  if errs.empty?
    puts "#{skill}: OK#{warns.empty? ? '' : "  (note: #{warns.join('; ')})"}"
  else
    failures += 1
    errs.each { |e| puts "#{skill}: FAIL #{e}" }
  end
end

missing.each do |d|
  failures += 1
  puts "#{File.basename(d)}: FAIL directory has no SKILL.md"
end

# AGENTS.md is not a skill, but it is the orchestrator's entry document, read
# first and in full, and this lane edits it.
AGENTS_MD = File.join(repo_root, 'AGENTS.md')
agents_checked = File.exist?(AGENTS_MD)
if agents_checked
  n = File.readlines(AGENTS_MD, encoding: 'UTF-8').length
  if n > AGENTS_CAP
    failures += 1
    puts "AGENTS.md: FAIL is #{n} lines, over its cap of #{AGENTS_CAP}"
  else
    puts 'AGENTS.md: OK'
  end
end

puts "#{files.length + missing.length} skill(s) checked#{agents_checked ? ' (plus AGENTS.md)' : ''}, #{failures} failing"
exit(failures.zero? ? 0 : 1)
