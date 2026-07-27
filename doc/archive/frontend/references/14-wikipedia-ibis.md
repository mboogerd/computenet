# Issue-Based Information System (IBIS)

- **Type**: Encyclopedia overview
- **Source**: https://en.wikipedia.org/wiki/Issue-based_information_system
- **Accessed**: 2026-07-11

## What it is

IBIS is an argumentation methodology developed by Werner Kunz and Horst Rittel in the 1960s for structuring group deliberation on complex ("wicked") problems. It became the notational ancestor of most modern computer-supported argument-mapping tools.

## Core elements (four node types, graph structure)

1. **Issues** — questions that need answering.
2. **Positions** — candidate answers to an issue.
3. **Arguments** — reasons for/against a position ("pros" and "cons").
4. **Nested issues** — new questions that surface during deliberation, attaching further sub-structure.

These are nodes connected by directed edges expressing relationships (responds-to, supports, objects-to, etc.) — i.e., structurally this *is* an argumentation graph, which is why IBIS-derived tools are the closest existing prior art to a "frontend for an argumentation graph backend."

## Tooling lineage

- **gIBIS** (1980s) — first graphical/hypertext implementation.
- **Compendium** — modern open-source descendant with full graphical IBIS support.
- **Dialogue Mapping** — a live-facilitation practice where a trained facilitator builds the IBIS map in real time during a meeting, projected on a shared screen (see 15, 16).

## Strengths (per the overview)

- Well suited to ill-defined, early-exploration-phase problems, precisely because it doesn't force premature structure.
- Increases transparency of how a decision/position emerged.
- Broadens participation and surfaces objections that would otherwise get lost in a linear conversation.
- Can reduce a group's tendency to re-litigate the same point repeatedly (since the point, once captured, is a visible node that can be pointed back to).

## Relevance

This is the direct conceptual ancestor of "argumentation graph" as a domain, and its four-node-type model (Issue/Position/Argument/nested-Issue) is a reasonable starting schema to check the agora module's actual domain model against. See index: ibis-notation, argumentation-graph-domain-model.
