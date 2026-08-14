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
files = Dir.glob(File.join(root, '*', 'SKILL.md')).sort
abort "no SKILL.md found under #{root}" if files.empty?

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
      raise 'frontmatter is not a mapping' unless y.is_a?(Hash)

      unknown = y.keys - ALLOWED - TOLERATED
      errs << "unexpected key(s): #{unknown.join(', ')}" unless unknown.empty?
      (y.keys & TOLERATED).each do |k|
        warns << "#{k}: valid in Claude Code, outside skill-creator's allowlist"
      end

      errs << "missing 'name'" unless y.key?('name')
      errs << "missing 'description'" unless y.key?('description')

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

  if errs.empty?
    puts "#{skill}: OK#{warns.empty? ? '' : "  (note: #{warns.join('; ')})"}"
  else
    failures += 1
    errs.each { |e| puts "#{skill}: FAIL #{e}" }
  end
end

puts "#{files.length} skill(s) checked, #{failures} failing"
exit(failures.zero? ? 0 : 1)
