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

export const defaultWakeTransport: WakeTransport = {
  wake: async (graphId) => {
    const res = await fetch(`/api/inspect/graph/${graphId}/wake`, { method: 'POST' });
    if (!res.ok) throw new Error(`HTTP ${res.status} for ${res.url}`);
    return (await res.json()) as WakeAck;
  },
};
