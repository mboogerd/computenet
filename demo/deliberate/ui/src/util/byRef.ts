import { createMemo, type Accessor } from 'solid-js';

/** Two ref lists are the same list: same refs in the same order. */
export const sameRefs = (a: readonly string[], b: readonly string[]) => a.length === b.length && a.every((r, i) => r === b[i]);

/**
 * A list of `{ ref }` items keyed by ref (computenet-syv45): every SSE frame is
 * all new objects, and `<For>` keys by identity, so iterating the objects
 * remounts every row each frame (flicker). Iterate `refs()` instead and read the
 * current item with `byRef(ref)`: a frame with the same refs updates in place.
 */
export function useByRef<T extends { ref: string }>(items: Accessor<readonly T[]>) {
  const refs = createMemo(() => items().map((i) => i.ref), undefined, { equals: sameRefs });
  const map = createMemo(() => new Map(items().map((i) => [i.ref, i] as const)));
  return { refs, byRef: (ref: string) => map().get(ref) };
}
