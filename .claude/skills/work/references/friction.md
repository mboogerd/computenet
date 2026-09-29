# Log friction (/work step 7)

How to record what SKILL.md step 7 asks for.

The SDLC epic is shared, so pull first. Search one distinctive word at a time
(`bd search` matches title substrings only): `bd search "<word>" --status all --json`.

- **Open match** → comment your instance (what you did, what happened, what it
  cost) with `bd comment <id> --file <file>`. If labelled `needs-evidence`,
  answer its last comment and `bd update <id> --remove-label=needs-evidence`.
- **Closed match** → file anew, citing it.
- **No match** → write description (what the skill says, what happened, what it
  cost) and acceptance (what would prevent it) to files, then:

```bash
.claude/skills/work/scripts/file-friction.sh --type <bug|feature> --title "<one line>" --desc-file <desc> --accept-file <accept> --skill-version <the epic's metadata.skill_version>
```

`bd comment` refused → `bd update <id> --append-notes "<plain text>"` (never
`--notes`, which overwrites) and name the refused command in the summary. /work step 6
pushes.
