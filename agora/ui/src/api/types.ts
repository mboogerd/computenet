export type Ref = string;
export type Polarity = 'ATTACK' | 'SUPPORT';
export type Kind = 'CLAIM' | 'EDGE';

/** The wire shape (civictech.agora AgoraApp.NodeDto). kotlinx serialization
 *  OMITS null/default fields, so everything except ref/kind/credence is
 *  optional on the wire and must be normalized (see diff.normalize). */
export interface NodeDto {
  ref: Ref;
  kind: Kind;
  text?: string | null;
  polarity?: Polarity | null;
  source?: Ref | null;
  target?: Ref | null;
  head?: boolean;
  credence: number;
}

/** The normalized client record. Treat as immutable — the diff reuses the
 *  previous object for any node whose fields are all unchanged, and the rest
 *  of the app leans on `prev === next` to mean "nothing changed here". */
export interface NodeRec {
  ref: Ref;
  kind: Kind;
  text: string | null;
  polarity: Polarity | null;
  source: Ref | null;
  target: Ref | null;
  head: boolean;
  credence: number;
}

export interface Delta {
  added: NodeRec[];
  removed: NodeRec[];
  changed: { prev: NodeRec; next: NodeRec }[];
  /** any add/remove, or a changed source/target/kind — as opposed to a pure
   *  credence move. Drives index rebuilds and Map-mode re-layout. */
  structural: boolean;
  /** first snapshot after a (re)connect — suppress pulses/ticker for it. */
  resync: boolean;
  /** wall-clock ms when applied (passed in; pure code never calls Date.now). */
  t: number;
}
