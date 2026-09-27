import type { GraphDto, Override } from '../api/types';

export type ConnState = 'connecting' | 'live' | 'reconnecting' | 'mock';

/** Where graph snapshots come from and where commands go. The live source
 *  talks to the backend; the mock source (`?mock`) scripts a growing tree. */
export interface GraphSource {
  start(onGraph: (g: GraphDto) => void, onState: (s: ConnState) => void): void;
  stop(): void;
  /** Submits a question; resolves to the new root claim ref. */
  ask(text: string): Promise<string | undefined>;
  override(id: string, mode: Override): Promise<void>;
}
