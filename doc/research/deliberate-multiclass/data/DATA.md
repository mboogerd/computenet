# Experiment data for REPORT.md: layout and replay guide

This is every model output and judgment the report's experiments produced. It
lets an algorithm change be re-evaluated **without regenerating anything**.
Only the parts the change actually touches need new model calls.

**Not in git.** Only this guide is tracked (`.gitignore` here ignores the rest),
because the data is about 25 MB (1.9 MB compressed). The data lives on the
machine that ran the research (`MacBoo`): in this folder of the research
worktree and in the repo-root `data/deliberate-multiclass/`, which is
ignored locally. Copy it into this folder to replay. All paths below are relative to
this `data/` folder. The scripts use relative paths, so run each one from its
own directory.

## Offline replay (verified)

Re-running the three accuracy analyses offline, with no `claude`, no `codex`
and no `TYPESAFE_API_KEY`, reproduces the saved outputs exactly:

| Command | Reproduces |
|---|---|
| `cd exp/e2e && python3 analyze.py` | `results.txt` (forecasting, 30 questions) |
| `cd exp/e2e2 && python3 analyze2.py` | `results2.txt` (knowledge + forecasting, 80 questions, leave-one-question-out CV) |
| `cd exp/recursive && python3 analyze_rec.py` | `summary.json` (recursive runs); values identical, key order may differ |

To test a different algorithm, edit the analysis script, or copy it and the
module it imports, and re-run it. The caches supply every model answer.

## What each change costs

| Change you want to test | New model calls | How |
|---|---|---|
| Combination rule, layer set, pooling, scale k, bearing→κ mapping, weighting of OUTSIDE_MY_KNOWLEDGE claims, redundancy damping, prior tempering | **None** | Edit `exp/e2e/analyze.py` (`arms_for`) or `exp/e2e2/analyze2.py`; the `P*` arms read `cache/jev.jsonl` and `cache/llm.jsonl` |
| Different aggregation over the **recursive** trees, using their final credence vectors | **None** | Edit `exp/recursive/analyze_rec.py`, which reads `runs/<qid>/graph.json` (per-node credence per layer, structure) |
| Different credence **semantics** inside the recursive trees (layers, clamps, K-class propagation) | **None**, in principle | Engine-level replay: start deliberate with `--data` on a *copy* of `runs/<qid>/data/`. Per SPEC DUR-01, `graph.jsonl` holds the structure and `host.journal` holds the Jev stances, and credence is recomputed on boot. Keep `claude` and `codex` off `PATH` so no new rounds can start. *Not exercised in this research.* |
| Different Jev judgment shape on the **same claims** (lik, compat, pairwise, new wording) | Jev only, about $0.05–0.20 per set | The claims are in `exp/e2e*/cache/llm.jsonl` (proposal entries); the request builders are in `run.py` / `run2.py` / `pilot/pilot.py` |
| Different proposer prompt, contrastive recursion, a new exploration policy (e.g. the `computenet-dw2wh` VoI fix), evidence retrieval | New generation | The exploration order decides which claims exist, so this cannot be replayed |
| Harder question set | New generation | See `exp/e2e2/build_questions.py` for the construction rules |

## Layout

| Path | Contents |
|---|---|
| `1ow0x-survey.md` | Running condensed notes from every round, in chronological order. Later sections supersede earlier ones; the report's §10 lists the reversals |
| `1ow0x-desc.md`, `1ow0x-design*.md`, `round3.md`, `round4.md`, `bug.md`, `bug2.md`, `dw2wh-notes.md` | Texts written into the beads |
| `combine/` | Round-1 combination simulation. **Known bugs** (see report §10); superseded by `verify/math/verify.py` |
| `voi/voi_categorical.py`, `exp/voi/`, `exp/voi2/` | VoI derivations; exact vs linear (`q1*.py`, `cat.py`, `q2.py`); invariance and non-circular references (`lib.py`, `inv.py`, `pop.py`, `ana.py`, `none.py`); Sol reviews (`sol*.md`) |
| `pilot/` | Jev pilot: `pilot.py` (`plan` / `run --live` / `analyze` / `selftest`, `--testset`), `testset.json` (original gold), `testset_llm.json` (Opus+Sol strict consensus gold), `responses.jsonl` (534 Jev responses, including a PRIOR arm), analysis outputs |
| `verify/gold/` | Blind Opus/Sol ratings (`raw/`, `ratings/`), `consensus.py`, `agreement.json`, gold variants |
| `verify/math/` | **Faithful Python ports of the Kotlin layers** (`verify.py`), matching to ≤ 5e-16 at K=2; the reference implementation for any rule change. Also `voi_check.py`, `c5b.py`, `c7.py`, Sol's review |
| `verify/cites/` | Citation check notes and `sol_out.txt` (fetched HTML excluded) |
| `exp/leak/` | Prior-leakage corrections and new shapes: `part_a.py`, `part_b.py`, `responses_b.jsonl` (94 Jev responses), `lib.py` metrics |
| `exp/none/` | None-of-these candidates and simulations (`none.py`, `run.py`, `revise*.py`), `design.md`, `sol.md`, `opus.md` |
| `exp/absolute/` | Absolute-vs-comparative gold (`items.json`, `ratings/`), `jev_responses.jsonl`, leak simulation (`sim.py`) |
| `exp/e2e/` | Forecasting: `questions.json` (30 Manifold markets with URLs), `markets_raw.json` / `manifold_cands.json` (selection provenance), `cache/llm.jsonl` (Opus/Sol proposals and direct distributions, with costs), `cache/jev.jsonl`, `analyze.py`, `results.txt`, `scores.json` |
| `exp/e2e2/` | Knowledge set: `questions_knowledge.json` (+ `.sha256`), Wikidata dumps `wd_*.json`, `cache/`, `run2.py`, `analyze2.py`, `results2.txt`, `scores2.json` |
| `exp/recursive/` | Real-engine runs: `drive.py`, `batch.py`, framing shim `bin/claude`, `pins.json`, `runs/<qid>/{graph.json,result.json,settings.json,server.log,data/}`, `analyze_rec.py`, `flat.py`, `summary.json` |

## Excluded, and how to regenerate

| Excluded | Regenerate with |
|---|---|
| `exp/recursive/dist/` (the engine build) | `./gradlew :demo:deliberate:installDist` at commit `c6dcef98` |
| `exp/voi2/pop.json` (24 MB simulated population) | `cd exp/voi2 && python3 pop.py` (seeded) |
| `exp/e2e2/supergpqa_hard.json` (raw dataset page dump) | `build_questions.py` fetches it from the HF datasets-server; the selected items are already in `questions_knowledge.json` |
| `pilot/requests.jsonl` | `python3 pilot.py plan` |
| `verify/cites/*.html`, the `sol.log` transcripts | Not needed for replay |

## Sources and licences

- **SuperGPQA** items in `exp/e2e2/questions_knowledge.json`: m-a-p/SuperGPQA,
  ODC-BY 1.0, arXiv 2502.14739. Options were reduced as described in
  `build_questions.py`.
- **Wikidata** items: CC0.
- **Manifold** market questions: public API; each question carries its URL.
- **Model outputs** from Opus 5.5, Sol and Jev (`jev-1.13.0`), 2026-09-28.

## Gotchas when re-running anything that calls models

- Give every `claude` / `codex exec` call an explicit timeout. Two `codex exec`
  runs hung for more than 80 minutes during this work.
- `codex exec` rejects `--search`.
- The Opus calls in `exp/e2e/cache` used the PATH `claude --model opus`. Later
  experiments used the newest bundled CLI with `--bare`, as in
  `.claude/skills/deliberate/scripts/deliberate.py`.
