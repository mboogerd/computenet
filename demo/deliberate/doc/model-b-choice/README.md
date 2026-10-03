# Model B premise-vs-bearing measurement harness

This directory makes the figures in
[`../research/model-b-choice.md`](../research/model-b-choice.md) reproducible.
It changes no runtime code.

The fixed corpus is every con argument at the nine non-root chain nodes in the
committed calibration material: 64 arguments from three live proposer-grown
deliberations. `make_corpus.py` extracts their exact model-B state. Selection is
complete and happens before labels or judge outputs; it is not balanced or
tuned to either method.

Gold uses independent, blind passes over the prompt from `label_prompt.py`.
Opus and Sonnet are primary; a fresh Opus pass adjudicates their disagreements.
Every pass sees the same rubric and no other rater or Jev answer.
`build_gold.py` preserves every vote and reason. This substitutes Sonnet for
the planned isolated Sol rater: the sandbox refused the separate Codex process
that would have sent the corpus to Sol. That model-family limitation belongs
in any interpretation of the numbers.

The alternative is predeclared before calls: two five-level Scores measure
premise dispute and bearing denial separately. Both expected levels below 2.0 (of 0-4) mean
`NEITHER`; otherwise the higher score wins, with a tie kept as
`DISPUTES_CLAIM` (the existing `ADD` route). No threshold is fitted to gold.

Run from this directory:

```bash
python3 make_corpus.py
python3 label_prompt.py > /tmp/model-b-label-prompt.txt
# Feed that prompt independently to Opus, Sonnet and a fresh Opus adjudicator
# pass, requesting the shape in gold-schema.json, then validate/capture each
# CLI response (names: opus, sonnet, opus-adjudicator):
python3 capture_rater.py /tmp/opus-envelope.json opus
python3 build_gold.py
TYPESAFE_API_KEY=... python3 run_jev.py
python3 score.py
```

Committed artifacts:

- `data/corpus.json`: fixed inputs and source provenance;
- `data/rater-{opus,sonnet,opus-adjudicator}.json`: raw blind labels and rationales;
- `data/gold.json`: resolution audit;
- `data/jev.json`: item predictions plus complete Jev responses and usage;
- `data/metrics.txt`: scorer output quoted by the research note.
