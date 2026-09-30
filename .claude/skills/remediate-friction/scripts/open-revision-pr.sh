#!/usr/bin/env bash
# Link every item a revision PR absorbs to that PR, so sweep-merged-prs.sh
# closes them when it merges.
#
# Why this exists: §4 step 4 said "close the items when it merges", but the
# revision PR merges hours after the session that opened it ends, and nothing
# carried the item list across. #1019's five items stayed open ~12h, #1131's
# five and #1191's two likewise, each closed by hand by a later session; left
# open they re-qualify the just-rewritten section for another revision
# (computenet-6nzf5). The sweep already closes any non-closed bead whose
# metadata.pr is a merged PR of this repo — blocked and `human`-labelled items
# included; only `deferred` ones are reported instead — so the whole fix is
# that every absorbed item carries metadata.pr. A prose step to write it was
# option B; this script is the version that cannot skip an item silently.
#
# metadata.pr is the CANONICAL url from `gh pr view`, never a bare number: the
# sweep joins on ^https?://<host>/<owner>/<repo>/pull/<n> and ignores anything
# else, so `pr=#1131` (as a handoff note once prescribed) would never close.
#
# Usage: open-revision-pr.sh <pr-number|pr-url> [--section '<file>#<heading>'] [<id>...]
#   --section  link every non-closed item labelled `revise` whose
#              metadata.section equals it, a `.claude/skills/` prefix ignored
#   <id>...    further items to link, e.g. the park item that asks a human
#              to approve the PR; at least one of --section or an id
# Run where `bd` resolves the main checkout's database.
# Exit: 0 = every item re-read with metadata.pr = the url; 1 = some item is not
#       linked (named on stderr); 2 = bad usage; 3 = gh/bd/jq unusable or the
#       section resolved no items.
set -uo pipefail

usage="usage: open-revision-pr.sh <pr-number|pr-url> [--section '<file>#<heading>'] [<id>...]"
die() { echo "open-revision-pr: $*" >&2; exit 3; }
[ $# -ge 2 ] || { echo "$usage" >&2; exit 2; }
pr=$1; shift
section=""; ids=""
while [ $# -gt 0 ]; do
  case "$1" in
    --section) [ -n "${2:-}" ] || { echo "$usage" >&2; exit 2; }
               section=$2; shift 2 ;;
    -*) echo "$usage" >&2; exit 2 ;;
    *) ids="$ids$1"$'\n'; shift ;;
  esac
done

# bd may print warnings before its JSON; keep from the first [ or { on.
bdjson() { sed -n '/^[[{]/,$p'; }

url=$(gh pr view "$pr" --json url --jq .url) && [ -n "$url" ] \
  || die "gh pr view $pr failed — no url to stamp"
[[ $url =~ ^https?://[^/]+/[^/]+/[^/]+/pull/[0-9]+$ ]] \
  || die "gh returned something that is not a PR url: $url"

if [ -n "$section" ]; then
  # Match on a normalised key, not bd's exact --metadata-field: items are
  # queued both as `.claude/skills/work/...` and as `work/...`, and an exact
  # match silently skipped the other spelling.
  queued=$(bd list --label revise --limit 0 --json | bdjson | jq -r --arg s "$section" '
             def n: sub("^\\./"; "") | sub("^\\.claude/skills/"; "");
             (if type=="array" then . else (.issues // []) end)[]
             | select(((.metadata.section // "") | n) == ($s | n)) | .id') \
    || die "bd list failed — cannot resolve the items of $section"
  [ -n "$queued" ] || die "no open item labelled revise has section=$section"
  ids="$queued"$'\n'"$ids"
fi
ids=$(printf '%s' "$ids" | awk 'NF && !seen[$0]++')

# One bd write per call; a failed write is not fatal here, because the re-read
# below is the verdict — a write that reports success but did not take is the
# case this script exists to catch.
while IFS= read -r id; do
  [ -n "$id" ] || continue
  bd update "$id" --set-metadata "pr=$url" >/dev/null 2>&1 \
    || echo "open-revision-pr: bd update $id failed" >&2
done <<< "$ids"

unlinked=""; n=0
while IFS= read -r id; do
  [ -n "$id" ] || continue
  n=$((n + 1))
  got=$(bd show "$id" --json 2>/dev/null | bdjson \
          | jq -r '(if type=="array" then .[0] else . end).metadata.pr // ""' 2>/dev/null)
  if [ "$got" = "$url" ]; then echo "linked: $id -> $url"
  else unlinked="$unlinked $id"; fi
done <<< "$ids"

if [ -n "$unlinked" ]; then
  echo "open-revision-pr: NOT linked to $url:$unlinked — sweep-merged-prs.sh will not close them" >&2
  exit 1
fi
echo "linked $n item(s); sweep-merged-prs.sh closes them once $url merges"
