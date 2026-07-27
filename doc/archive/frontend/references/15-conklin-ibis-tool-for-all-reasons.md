# IBIS: A Tool for All Reasons

- **Type**: Practitioner paper (Jeff Conklin, CogNexus Institute — companion to "Dialogue Mapping: Building Shared Understanding of Wicked Problems")
- **Source**: https://www.cognexus.org/IBIS-A_Tool_for_All_Reasons.pdf
- **Accessed**: 2026-07-11

## Why the notation is minimal by design

> "I have never run into an interaction that could not be expressed in Questions, Ideas, Pros, and Cons."

The claim is that IBIS's small, fixed vocabulary (vs. a richer taxonomy of relation types) is a *feature* — it's simple enough to use live, in real time, during a heated discussion, and it's expressive enough to hold any interaction that actually comes up.

## Why external representation matters cognitively

> "The power of IBIS as a notation is that it organizes all of the issues, positions, information, and assumptions so that all participants have the issue map as a point of reference."

This is a working-memory argument: an unmapped group discussion depends on everyone's short-term memory of what's been said; a live map externalizes that state so the group's shared understanding doesn't degrade as the conversation gets long or heated.

## Neutralizing bad-faith rhetorical dynamics

Because arguments are captured as discrete, visible nodes, repeating an already-answered point becomes visibly redundant: "If someone restates an argument after it's been captured, the Dialog Mapper can point to it in the map" — the map itself becomes a shared, checkable record rather than relying on someone's memory or authority to call it out.

## Facilitation is a skill, not just software

Conklin frames competent live mapping as a three-stage skill progression (slow deliberate transcription → real-time fluent capture), governed by a "listening cycle: listening → guessing → writing → validating." Crucially: **"Dialog Mapping is not about being psychic; rather it is about being willing to guess and to check the guess."** The tool supports a provisional, correctable capture loop, not a one-shot perfect transcription.

## Notation over software

Conklin explicitly endorses low-tech IBIS-on-a-whiteboard as equally valid to Compendium — the argument is that the *notation's* structure is what carries the cognitive benefit; the software is a convenience, not the source of the value.

## Relevance

Two design implications for a graph-backed frontend: (1) captured arguments should be easy to **point back to** (deep-linkable/highlightable) so a moderator or participant can say "this was already raised, see here" — a feature the flat-node structure of IBIS specifically enables; (2) editing/authoring needs to support a "provisional, correctable" workflow (draft → validate → commit), not force one-shot perfect entry. See index: real-time-authoring, provenance, dedup-and-cross-reference.
