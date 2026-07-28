import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { GraphList } from '../src/api/types';

// `solid/routeState.ts` parses `location.hash` at module load, and the suite
// runs in vitest's `node` environment (vite.config.ts) where there is no
// `location`. `vi.hoisted` runs before the imports below, which is the only
// place a module-load-time global can be established from a test file.
vi.hoisted(() => {
  (globalThis as unknown as { location: { hash: string } }).location = { hash: '' };
});

import {
  askToWake,
  cancelWake,
  clearWake,
  confirmingWake,
  currentGraphCold,
  lastWake,
  wakeError,
  wakeGraph,
  waking,
  setWakeTransport,
} from '../src/solid/cold';
import { fetchGraphs, setGraphsTransport } from '../src/solid/graphs';
import { setCurrentGraphId } from '../src/solid/routeState';
import type { WakeAck } from '../src/sync/coldClient';

const COLD = 'g-cold';
const HOT = 'g-hot';

function list(): GraphList {
  return {
    graphs: [
      {
        id: HOT,
        name: 'skillmatch',
        cells: 16,
        hosts: 1,
        nets: 1,
        health: { deadLetters: 0, parked: 0, restarts: 0 },
        lifecycle: 'hot',
      },
      {
        id: COLD,
        name: null,
        cells: 2,
        hosts: 1,
        nets: 1,
        health: { deadLetters: 0, parked: 0, restarts: 0 },
        lifecycle: 'cold',
      },
    ],
  };
}

/** Flush the microtask queue so the store's chained `.then()`s settle. */
async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

/** M5-COLD ticket Implement §2 / Tests: "wake confirmation flow". The wake is
 *  the only write the whole inspector performs, so what these pin is as much
 *  about what it does *not* do — nothing before the confirmation, nothing for
 *  a graph the user is not in. */
describe('solid/cold', () => {
  let wake: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    clearWake();
    setGraphsTransport({ fetchList: async () => list() });
    fetchGraphs();
    await flush();
    wake = vi.fn(async (graphId: string): Promise<WakeAck> => ({ graph: graphId, hosts: 0, cells: 2 }));
    setWakeTransport({ wake });
    setCurrentGraphId(COLD);
  });

  it('reports the graph the user is inside as cold, and a hot one as not', () => {
    expect(currentGraphCold()).toBe(true);
    setCurrentGraphId(HOT);
    expect(currentGraphCold()).toBe(false);
  });

  it('pressing Wake only opens the confirmation — nothing is sent yet', () => {
    askToWake();

    expect(confirmingWake()).toBe(true);
    expect(wake).not.toHaveBeenCalled();
    expect(waking()).toBe(false);
  });

  it('cancelling closes the dialog and still sends nothing', () => {
    askToWake();
    cancelWake();

    expect(confirmingWake()).toBe(false);
    expect(wake).not.toHaveBeenCalled();
  });

  it('confirming POSTs the wake for the current graph and keeps its 202 ack', async () => {
    askToWake();
    wakeGraph();

    expect(confirmingWake()).toBe(false);
    expect(waking()).toBe(true);
    await flush();

    expect(wake).toHaveBeenCalledTimes(1);
    expect(wake).toHaveBeenCalledWith(COLD);
    expect(waking()).toBe(false);
    expect(lastWake()).toEqual({ graph: COLD, hosts: 0, cells: 2 });
    expect(wakeError()).toBeNull();
  });

  it('a failed wake surfaces as an error and stops the pending state, without throwing', async () => {
    const boom = new Error('nope');
    setWakeTransport({ wake: async () => Promise.reject(boom) });
    vi.spyOn(console, 'error').mockImplementation(() => undefined);

    askToWake();
    wakeGraph();
    await flush();

    expect(waking()).toBe(false);
    expect(wakeError()).toBe(boom);
    vi.restoreAllMocks();
  });

  it('sends nothing at all when no graph is open', async () => {
    setCurrentGraphId(null);

    askToWake();
    wakeGraph();
    await flush();

    expect(wake).not.toHaveBeenCalled();
    expect(waking()).toBe(false);
  });

  it('clearWake resets the dialog, the pending flag and a previous error', async () => {
    setWakeTransport({ wake: async () => Promise.reject(new Error('nope')) });
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    askToWake();
    wakeGraph();
    await flush();
    expect(wakeError()).not.toBeNull();

    clearWake();

    expect(confirmingWake()).toBe(false);
    expect(waking()).toBe(false);
    expect(wakeError()).toBeNull();
    expect(lastWake()).toBeNull();
    vi.restoreAllMocks();
  });

  /** The transition back to live is driven by the server's own feed — the
   *  refetch on the ack only makes the common case immediate. */
  it('a woken graph stops reporting cold once the refreshed list says hot', async () => {
    setGraphsTransport({
      fetchList: async () => ({ graphs: list().graphs.map((g) => ({ ...g, lifecycle: 'hot' as const })) }),
    });

    askToWake();
    wakeGraph();
    await flush();

    expect(currentGraphCold()).toBe(false);
  });
});
