import { createSignal } from 'solid-js';
import type { GraphDto } from '../api/types';
import { MockSource } from '../mock/mockSource';
import type { ConnState, GraphSource } from './source';
import { LiveSource } from './sse';

const EMPTY: GraphDto = { questions: [], nodes: [] };

export const isMock = new URLSearchParams(window.location.search).has('mock');

/** The one graph source for this page: live SSE, or the scripted mock. */
export const source: GraphSource = isMock ? new MockSource() : new LiveSource();

const [graph, setGraph] = createSignal<GraphDto>(EMPTY);
const [conn, setConn] = createSignal<ConnState>('connecting');

export { graph, conn };

/** Starts feeding `graph` from the source. Every frame is a full snapshot and
 *  simply replaces the previous one; views key by ref so DOM survives. */
export function startSync(): () => void {
  source.start(setGraph, setConn);
  return () => source.stop();
}
