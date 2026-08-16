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
# THE SECOND TIER OF CRITERIA, added 2026-08-15. quick_validate.py only ever
# covered frontmatter, so this script did too — and a skills-rubric pass run
# by hand found three shape failures it could not have caught, because they
# live in skill-creator's Skill Writing Guide rather than its script:
# "Keep SKILL.md under 500 lines" and "For large reference files (>300
# lines), include a table of contents". Both are now checked here.
#
# Their severities differ deliberately. A missing ToC is mechanical, so it
# FAILS. The 500-line ideal is a design question — .claude/skills/work is a
# 1200-line operational procedure executed top to bottom, and splitting it
# changes what is in context at each step — so it WARNS, loudly, rather than
# blocking every future edit on a restructure nobody has agreed. A file with
# no `##` sections at all cannot have a meaningful ToC either, so that warns
# too and names the real problem instead of demanding an index of nothing.
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

# A skill directory with no SKILL.md is a broken skill, not an absence — report
# it rather than skipping silently.
missing = Dir.glob(File.join(root, '*')).select { |d| File.directory?(d) } -
          files.map { |f| File.dirname(f) }

# skill-creator: "Keep SKILL.md under 500 lines; if you're approaching this
# limit, add an additional layer of hierarchy along with clear pointers about
# where the model using the skill should go next to follow up."
BODY_LINE_IDEAL = 500
# skill-creator: "For large reference files (>300 lines), include a table of
# contents."
REFERENCE_TOC_THRESHOLD = 300

# A ToC is any heading that reads as one. Matching the heading rather than a
# list shape keeps this from mistaking the first ordered list in the body for
# an index.
def toc?(text)
  text.match?(/^##+\s+(contents|table of contents)\b/i)
end

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

  # Shape, not frontmatter. Counted on the body below the frontmatter, since
  # the guide's budget is about what lands in context when the skill fires.
  body_lines = body.sub(/\A---\n.*?\n---\n/m, '').lines.length
  if body_lines > BODY_LINE_IDEAL
    warns << "SKILL.md body is #{body_lines} lines (ideal <=#{BODY_LINE_IDEAL}); " \
             'add a layer of hierarchy and point into it'
  end

  Dir.glob(File.join(File.dirname(f), 'references', '*.md')).sort.each do |r|
    text = File.read(r, encoding: 'UTF-8')
    next unless text.lines.length > REFERENCE_TOC_THRESHOLD

    rel = File.join('references', File.basename(r))
    if text.scan(/^## /).length < 2
      warns << "#{rel} is #{text.lines.length} lines with no sections to index"
    elsif !toc?(text)
      errs << "#{rel} is #{text.lines.length} lines (>#{REFERENCE_TOC_THRESHOLD}) with no Contents"
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

puts "#{files.length + missing.length} skill(s) checked, #{failures} failing"
exit(failures.zero? ? 0 : 1)
