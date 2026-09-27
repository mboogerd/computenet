#!/usr/bin/env bash
# File one session's retro record: a bead of custom type `retro` that the
# weekly /retrospective reads. Written at the end of every /work and
# /remediate-friction session, including sessions that did nothing — an empty
# run is itself a data point. Template and vocabulary: references/retro.md.
#
# Usage:
#   file-retro.sh --skill <work|remediate-friction|...> --file <retro.md> \
#     [--skill-version <sha>] [--started <iso8601>] [--model <id>]
#
# Unparented on purpose: a hash id cannot collide across machines (AGENTS.md,
# "Creating tickets under a shared epic"), and no epic's ready-listing should
# see a retro. It does not push — the caller's publish step does.
# Prints the new bead id. Exit 1 on a failed step, 2 on bad usage/template.
set -uo pipefail
skill= file= ver= started= model=${CLAUDE_MODEL:-}
while [ $# -gt 0 ]; do case $1 in
  --skill) skill=$2; shift 2 ;; --file) file=$2; shift 2 ;;
  --skill-version) ver=$2; shift 2 ;; --started) started=$2; shift 2 ;;
  --model) model=$2; shift 2 ;;
  *) echo "file-retro: unknown argument $1" >&2; exit 2 ;; esac; done
[ -n "$skill" ] && [ -r "$file" ] || { echo "usage: file-retro.sh --skill S --file F [--skill-version V] [--started T] [--model M]" >&2; exit 2; }

# The template is the contract the retrospective parses: refuse a record
# missing a section rather than file one it cannot read.
for h in '## Summary' '## Outcomes' '## Issues' '## Judgment calls' '## Time' '## Handoff'; do
  grep -qx "$h" "$file" || { echo "file-retro: $file lacks the '$h' heading (see references/retro.md)" >&2; exit 2; }
done
# Every issue line carries a tag from the vocabulary, or other:<word>.
bad=$(awk '/^## Issues$/{f=1;next} /^## /{f=0} f && /^- /' "$file" \
  | grep -vE '^- \[(deviation|refusal|flake|misleading-instruction|tooling|unverified|surprise|reporting-miss|slow|none|other:[a-z0-9-]+)\] ')
[ -z "$bad" ] || { echo "file-retro: issue lines need a [tag] from references/retro.md:" >&2; echo "$bad" >&2; exit 2; }

# The custom type lives in the Dolt config and syncs, but a machine that has
# not pulled since it was added would refuse the create.
case ",$(bd config get types.custom 2>/dev/null | tail -1)," in *,retro,*) ;; *)
  cur=$(bd config get types.custom 2>/dev/null | tail -1); case $cur in ''|*"not set"*) new=retro ;; *) new="$cur,retro" ;; esac
  bd config set types.custom "$new" >/dev/null || { echo "file-retro: could not register the retro type" >&2; exit 1; } ;;
esac

machine=${BEADS_ACTOR:-$(hostname -s)}
now=$(date -u +%FT%TZ)
meta=$(jq -nc --arg s "$skill" --arg m "$machine" --arg v "$ver" --arg st "$started" --arg e "$now" --arg mo "$model" \
  '{retro_skill:$s, retro_machine:$m, skill_version:$v, started:$st, ended:$e, model:$mo} | with_entries(select(.value != ""))')
# stderr stays out of the id: a warning printed after a successful create
# would otherwise read as failure, and a retry would file the record twice.
err=$(mktemp "${TMPDIR:-/tmp}/file-retro.XXXXXX")
id=$(bd create "retro: $skill on $machine $(date -u +%F)" --type=retro --priority=4 --labels=retro \
       --body-file "$file" --metadata "$meta" --silent 2>"$err" | tail -1)
case $id in computenet-*) rm -f "$err"; echo "$id" ;;
  *) echo "file-retro: bd create failed: $id $(tail -3 "$err")" >&2; rm -f "$err"; exit 1 ;; esac
