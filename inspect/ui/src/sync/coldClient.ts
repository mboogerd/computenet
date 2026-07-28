/** `POST /api/inspect/graph/{id}/wake` (M5-COLD ticket Implement §1) — the
 *  server's 202 body: which graph, and how much it took to wake it.
 *
 *  `hosts` is not decoration. A drain is a whole-host act and so is its undo:
 *  resuming one drained host reactivates every cell it holds, including cells
 *  of components the user did not ask about. The UI says so rather than
 *  implying the wake was confined to the graph. */
export interface WakeAck {
  graph: string;
  /** Drained hosts resumed — each reactivates *all* of its cells. */
  hosts: number;
  /** Individually suspended cells resumed. */
  cells: number;
}

/** Its own transport interface, like `GraphsTransport`/`ErrorsTransport` — a
 *  plain request/response, kept behind an interface so `solid/cold.ts` is
 *  unit-testable with a mock and so the one write this client can perform is
 *  visible in exactly one place. */
export interface WakeTransport {
  wake(graphId: string): Promise<WakeAck>;
}

/** T19 — required by the server on every wake POST (see
 *  `InspectorServer.WAKE_HEADER` / its KDoc on `serveGraph`): forces a
 *  cross-origin caller's request to be preflighted, which this server
 *  answers with no `OPTIONS` handler, so it fails closed. Same-origin here
 *  (the dev UI's own traffic, proxied by `vite.config.ts`) triggers no CORS
 *  machinery at all — the header is just a plain header on a same-origin
 *  request. */
const WAKE_HEADER = 'X-Inspector';
const WAKE_HEADER_VALUE = '1';

export const defaultWakeTransport: WakeTransport = {
  wake: async (graphId) => {
    const res = await fetch(`/api/inspect/graph/${graphId}/wake`, {
      method: 'POST',
      headers: { [WAKE_HEADER]: WAKE_HEADER_VALUE },
    });
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${res.url}`);
    return (await res.json()) as WakeAck;
  },
};
