import type { Delta, NodeDto, NodeRec, Ref } from '../api/types';

/** Fill the wire's omitted null/default fields. */
export function normalize(dto: NodeDto): NodeRec {
  return {
    ref: dto.ref,
    kind: dto.kind,
    text: dto.text ?? null,
    polarity: dto.polarity ?? null,
    source: dto.source ?? null,
    target: dto.target ?? null,
    head: dto.head ?? false,
    credence: dto.credence,
  };
}

function equal(a: NodeRec, b: NodeRec): boolean {
  return (
    a.kind === b.kind &&
    a.text === b.text &&
    a.polarity === b.polarity &&
    a.source === b.source &&
    a.target === b.target &&
    a.head === b.head &&
    a.credence === b.credence
  );
}

/** Diff an absolute snapshot against prior state. Unchanged nodes keep their
 *  previous object identity — the invariant everything downstream relies on. */
export function diffSnapshot(
  prev: ReadonlyMap<Ref, NodeRec>,
  snapshot: readonly NodeDto[],
  opts: { resync?: boolean; now: number },
): { next: Map<Ref, NodeRec>; delta: Delta } {
  const next = new Map<Ref, NodeRec>();
  const added: NodeRec[] = [];
  const changed: { prev: NodeRec; next: NodeRec }[] = [];

  for (const dto of snapshot) {
    const cand = normalize(dto);
    const before = prev.get(dto.ref);
    if (before && equal(before, cand)) {
      next.set(dto.ref, before); // identity preserved
    } else if (before) {
      next.set(dto.ref, cand);
      changed.push({ prev: before, next: cand });
    } else {
      next.set(dto.ref, cand);
      added.push(cand);
    }
  }

  const removed: NodeRec[] = [];
  for (const [ref, rec] of prev) {
    if (!next.has(ref)) removed.push(rec);
  }

  const structural =
    added.length > 0 ||
    removed.length > 0 ||
    changed.some(
      (c) =>
        c.prev.source !== c.next.source ||
        c.prev.target !== c.next.target ||
        c.prev.kind !== c.next.kind,
    );

  return {
    next,
    delta: { added, removed, changed, structural, resync: opts.resync ?? false, t: opts.now },
  };
}
