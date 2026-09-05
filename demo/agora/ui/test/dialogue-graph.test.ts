import { describe, it, expect } from 'vitest';
import { GraphStore } from '../src/sync/store';
import { diffSnapshot } from '../src/sync/diff';
import type { NodeDto } from '../src/api/types';
import fixture from './fixtures/dialogue-graph.json';

/**
 * BS-20 ([AGO1-OBS-01]) — a real `/graph` response from
 * `civictech.dialogue.DialogueApp`, fed through demo/agora/ui's own
 * parsing/diff layer (`GraphStore.applySnapshot` / `diffSnapshot`), the same
 * layer sync.test.ts exercises against agora's own fixtures. The point is
 * not new frontend code — none is added — but proof that a dialogue-built
 * graph is byte-shape-compatible with what agora's frontend already parses,
 * per epic computenet-2aw §2.4/§3.6 and feature computenet-2aw.5.
 *
 * ## Capture procedure (how `fixtures/dialogue-graph.json` was produced)
 *
 * ```
 * ./gradlew :demo:dialogue:run --args="8090 --transcript $(pwd)/demo/dialogue/src/test/resources/bs20-because.jsonl --extractor rule"
 * curl -s -X POST -d 'action=replay&pace=max' localhost:8090/transcript
 * # poll GET /transcript until counts.pending == 0 (all 6 utterances settled)
 * curl -s localhost:8090/graph | python3 -m json.tool > demo/agora/ui/test/fixtures/dialogue-graph.json
 * ```
 *
 * Captured 2026-09-04 against `demo/dialogue/src/test/resources/bs20-because.jsonl`
 * (6 utterances, 3 speakers, 4 "X because Y." sentences, one plain claim, one
 * disagreement-marker line) run with `--extractor rule`
 * (`civictech.dialogue.extract.RuleExtractor`). All 6 utterances settled with
 * zero rejected/failed segments. Commit the capture as-is: refs come from
 * `BindingTable.refFor`, a pure function of the canonical claim/relation key,
 * so a re-capture is stable up to array order.
 *
 * `bs20-because.jsonl` is F5's capture/manual-run input only — the real demo
 * transcript + cassette for the property gate is feature computenet-2aw.6's,
 * not this one.
 *
 * Nothing here asserts the NUMBER of claims: sibling computenet-i6hp may
 * change how many claims `RuleExtractor` mints per "because" segment.
 */

const clone = (x: unknown): NodeDto[] => structuredClone(x) as NodeDto[];

describe('dialogue /graph fixture (BS-20)', () => {
  it('normalizes without error into a non-empty node set with at least one EDGE', () => {
    const store = new GraphStore();
    expect(() => store.applySnapshot(clone(fixture), { now: 0 })).not.toThrow();

    expect(store.nodes.size).toBeGreaterThan(0);

    const edges = [...store.nodes.values()].filter((n) => n.kind === 'EDGE');
    expect(edges.length).toBeGreaterThan(0);
  });

  it('every EDGE resolves to nodes present in the store and indexed both ways', () => {
    const store = new GraphStore();
    store.applySnapshot(clone(fixture), { now: 0 });

    const edges = [...store.nodes.values()].filter((n) => n.kind === 'EDGE');
    for (const edge of edges) {
      expect(edge.source, `edge ${edge.ref} has a source`).toBeTruthy();
      expect(edge.target, `edge ${edge.ref} has a target`).toBeTruthy();

      const sourceNode = store.get(edge.source!);
      const targetNode = store.get(edge.target!);
      expect(sourceNode, `edge ${edge.ref}'s source ${edge.source} resolves`).toBeDefined();
      expect(targetNode, `edge ${edge.ref}'s target ${edge.target} resolves`).toBeDefined();

      expect(store.outgoing.get(edge.source!)).toContain(edge.ref);
      expect(store.incoming.get(edge.target!)).toContain(edge.ref);
    }
  });

  it('normalizes CLAIM records to null polarity/source and head:false', () => {
    const { next } = diffSnapshot(new Map(), clone(fixture), { now: 0 });
    const claims = [...next.values()].filter((n) => n.kind === 'CLAIM');
    expect(claims.length).toBeGreaterThan(0);
    for (const claim of claims) {
      expect(claim.polarity).toBeNull();
      expect(claim.source).toBeNull();
      expect(claim.head).toBe(false);
    }
  });

  it('every credence is a finite number in [0,1]', () => {
    const store = new GraphStore();
    store.applySnapshot(clone(fixture), { now: 0 });
    for (const rec of store.nodes.values()) {
      expect(Number.isFinite(rec.credence)).toBe(true);
      expect(rec.credence).toBeGreaterThanOrEqual(0);
      expect(rec.credence).toBeLessThanOrEqual(1);
    }
  });

  it('a fresh diffSnapshot against an empty store adds every fixture node', () => {
    const { delta } = diffSnapshot(new Map(), clone(fixture), { now: 0 });
    expect(delta.added.length).toBe((fixture as NodeDto[]).length);
    expect(delta.removed.length).toBe(0);
    expect(delta.changed.length).toBe(0);
    expect(delta.structural).toBe(true);
  });
});
