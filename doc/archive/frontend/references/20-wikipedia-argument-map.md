# Argument Map (Wikipedia)

- **Type**: Encyclopedia overview
- **Source**: https://en.wikipedia.org/wiki/Argument_map
- **Accessed**: 2026-07-11

## Standard notations

- **Box-and-arrow** — the dominant modern convention: propositions in boxes, arrows showing inferential relationships (reason → conclusion), typically tree-shaped, though no fixed convention on vertical orientation (root at top vs. bottom varies by tool).
- **Toulmin model** (Stephen Toulmin, 1958) — adds the concept of a **warrant**: not just premise → conclusion, but an explicit representation of "the reasons behind the inference, the backing that authorizes the link." This is a richer relation model than plain support/attack.
- **IBIS** (1970s) — issue/position/argument hierarchy (see 14-16, 19); implemented in gIBIS and descendants.

## Software landscape by collaboration scale

- **Single-user**: Rationale, Araucaria, bCisive.
- **Small group / facilitated**: Compendium, Digalo, Belvedere.
- **Community-scale**: Debategraph, Kialo (identified as the most widely adopted as of 2020).
- **Open-source / text-based**: Argdown, Argüman.

## Noted criticisms

- **Cognitive load in classroom use**: complex maps can "increase cognitive load beyond what is optimal for learning," and require real coaching investment — a caution against assuming "more structure is always better" for novice users specifically.
- **Accessibility gap**: most tools in this space don't accommodate visual disabilities well (Argumentation.io flagged as an exception).
- **No reliable automated analysis**: current tools "cannot reliably automate analysis or synthesis of arguments" the way statistical software automates data analysis — argument quality/validity assessment stays a human judgment call, not something the UI can safely automate away.

## Relevance

The Toulmin "warrant" concept is a useful check against a plain support/attack edge model: if the agora backend's schema only has two relation types, there may be a real expressiveness gap for representing *why* a premise supports a conclusion (as opposed to just *that* it does) — worth checking against the actual backend schema. See index: relation-type-encoding, argumentation-graph-domain-model, toulmin-model.
