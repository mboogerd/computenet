import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CellDetail, CellState, StateSummaryPayload } from '../src/api/types';
import { DetailController, type DetailTransport } from '../src/sync/detailClient';

function detail(ref: string): CellDetail {
  return {
    ref,
    name: ref,
    typeFqn: 'civictech.cell.data.SetCell',
    color: 'PURE',
    manifests: [],
    ports: [],
    host: 'h1',
    net: 'local',
    lifecycle: 'HOT',
    generation: 0,
    graph: null,
    attention: null,
    links: { inbound: 0, outbound: 0, taps: 0 },
  };
}

function state(ref: string): CellState {
  return { ref, frontier: null, kind: 'view', value: 1, staleMs: 0 };
}

function summary(ref: string, overrides: Partial<StateSummaryPayload> = {}): StateSummaryPayload {
  return { ref, cardinality: '3 rows', frontier: { source: 'host0000', counter: 3 }, staleMs: 0, ...overrides };
}

describe('DetailController', () => {
  let transport: {
    fetchDetail: ReturnType<typeof vi.fn>;
    fetchState: ReturnType<typeof vi.fn>;
    observeStart: ReturnType<typeof vi.fn>;
    observeStop: ReturnType<typeof vi.fn>;
  };
  let onDetail: ReturnType<typeof vi.fn>;
  let onState: ReturnType<typeof vi.fn>;
  let controller: DetailController;

  beforeEach(() => {
    transport = {
      fetchDetail: vi.fn(async (ref: string) => detail(ref)),
      fetchState: vi.fn(async (ref: string) => state(ref)),
      observeStart: vi.fn(async () => undefined),
      observeStop: vi.fn(async () => undefined),
    };
    onDetail = vi.fn();
    onState = vi.fn();
    controller = new DetailController(transport as unknown as DetailTransport, { onDetail, onState });
  });

  it('selecting a node issues exactly one observe POST and fetches detail + state', async () => {
    controller.select('a');
    await flush();

    expect(transport.observeStart).toHaveBeenCalledTimes(1);
    expect(transport.observeStart).toHaveBeenCalledWith('a');
    expect(transport.observeStop).not.toHaveBeenCalled();
    expect(transport.fetchDetail).toHaveBeenCalledWith('a');
    expect(transport.fetchState).toHaveBeenCalledWith('a');
    expect(onDetail).toHaveBeenCalledWith('a', detail('a'));
    expect(onState).toHaveBeenCalledWith('a', state('a'));
  });

  it('deselecting issues exactly one observe DELETE for the previously-selected ref', async () => {
    controller.select('a');
    await flush();
    controller.deselect();
    await flush();

    expect(transport.observeStop).toHaveBeenCalledTimes(1);
    expect(transport.observeStop).toHaveBeenCalledWith('a');
  });

  it('selecting a different node releases the previous observation and starts exactly one new one', async () => {
    controller.select('a');
    await flush();
    controller.select('b');
    await flush();

    expect(transport.observeStop).toHaveBeenCalledTimes(1);
    expect(transport.observeStop).toHaveBeenCalledWith('a');
    expect(transport.observeStart).toHaveBeenCalledTimes(2);
    expect(transport.observeStart).toHaveBeenNthCalledWith(2, 'b');
  });

  it('re-selecting the currently-selected ref is a no-op (no duplicate observe calls)', async () => {
    controller.select('a');
    await flush();
    controller.select('a');
    await flush();

    expect(transport.observeStart).toHaveBeenCalledTimes(1);
    expect(transport.observeStop).not.toHaveBeenCalled();
  });

  it('deselecting with nothing selected issues no observe call', () => {
    controller.deselect();
    expect(transport.observeStop).not.toHaveBeenCalled();
  });

  it('onSummary ignores a summary for a non-selected ref (existing behavior preserved)', async () => {
    controller.select('a');
    await flush();
    onState.mockClear();
    transport.fetchState.mockClear();

    controller.onSummary(summary('b')); // not selected — ignored
    await flush();
    expect(transport.fetchState).not.toHaveBeenCalled();
  });

  it('onSummary refetches on the first summary since selection (prev undefined -> indicatesChange true)', async () => {
    controller.select('a');
    await flush();
    transport.fetchState.mockClear();

    controller.onSummary(summary('a'));
    await flush();
    expect(transport.fetchState).toHaveBeenCalledTimes(1);
    expect(transport.fetchState).toHaveBeenCalledWith('a');
  });

  /** V1A-FE ticket Implement §1 / acceptance criteria: the load-bearing case.
   *  Once V1A-BE's coalesced feed publishes even-when-quiet windows, a naive
   *  "refetch on every summary" turns into a 1 Hz polling loop against an
   *  unchanged value — this is the gate that prevents that. */
  describe('change-gated refetch', () => {
    it('a run of quiet summaries triggers zero further fetchState calls; the next changed summary triggers exactly one', async () => {
      controller.select('a');
      await flush();
      transport.fetchState.mockClear();

      const base = summary('a', { cardinality: '3 rows', frontier: { source: 'host0000', counter: 3 }, staleMs: 0 });
      controller.onSummary(base); // first summary since selection -> refetches
      await flush();
      transport.fetchState.mockClear();

      controller.onSummary({ ...base, staleMs: 1000 }); // quiet window
      controller.onSummary({ ...base, staleMs: 2000 }); // quiet window
      controller.onSummary({ ...base, staleMs: 3000 }); // quiet window
      await flush();
      expect(transport.fetchState).not.toHaveBeenCalled();

      controller.onSummary({
        ...base,
        staleMs: 0,
        cardinality: '4 rows',
        frontier: { source: 'host0000', counter: 4 },
      }); // an effective change settled
      await flush();
      expect(transport.fetchState).toHaveBeenCalledTimes(1);
      expect(transport.fetchState).toHaveBeenCalledWith('a');
    });

    it('a descriptor-only (cold) selection still refetches nothing on any summary, changed or not', async () => {
      controller.select('a', 'descriptor');
      await flush();

      controller.onSummary(summary('a', { staleMs: 0 }));
      controller.onSummary(summary('a', { staleMs: 0, cardinality: '9 rows' }));
      await flush();

      expect(transport.fetchState).not.toHaveBeenCalled();
    });

    it('re-selecting the same ref always refetches once, even if the next summary looks quiet', async () => {
      controller.select('a');
      await flush();
      controller.onSummary(summary('a', { staleMs: 0 }));
      await flush();

      controller.deselect();
      await flush();
      controller.select('a');
      await flush();
      transport.fetchState.mockClear();

      // Same-looking payload as before the deselect/reselect — but the held
      // "last seen" payload was cleared on select(), so this is a first
      // summary again and must refetch.
      controller.onSummary(summary('a', { staleMs: 0 }));
      await flush();
      expect(transport.fetchState).toHaveBeenCalledTimes(1);
    });

    it('a fetchState triggered by onSummary is still discarded by the epoch guard if superseded by a re-selection before it resolves', async () => {
      let resolveA!: (s: CellState) => void;
      transport.fetchState.mockImplementation(
        (ref: string) =>
          new Promise<CellState>((resolve) => {
            if (ref === 'a') resolveA = resolve;
            else resolve(state(ref));
          }),
      );

      controller.select('a');
      await flush(); // initial fetchState('a') is pending, never resolved in this test
      onState.mockClear();

      controller.onSummary(summary('a')); // first summary since selection -> another pending fetchState('a')
      await flush();

      controller.select('b'); // supersedes 'a' before either fetchState('a') resolves
      await flush();

      resolveA(state('a'));
      await flush();

      expect(onState).not.toHaveBeenCalledWith('a', expect.anything());
    });
  });

  it('discards a stale response from a superseded selection (rapid re-select)', async () => {
    let resolveA!: (d: CellDetail) => void;
    transport.fetchDetail.mockImplementation(
      (ref: string) => new Promise<CellDetail>((resolve) => (ref === 'a' ? (resolveA = resolve) : resolve(detail(ref)))),
    );

    controller.select('a'); // fetchDetail('a') is now pending
    controller.select('b'); // supersedes 'a' before it resolves
    await flush();

    resolveA(detail('a')); // 'a' finally resolves — must be dropped, not shown for 'b'
    await flush();

    expect(onDetail).not.toHaveBeenCalledWith('a', expect.anything());
    expect(onDetail).toHaveBeenCalledWith('b', detail('b'));
  });

  it('reports a fetch failure via the handler with the ref and error, without throwing', async () => {
    const err = new Error('boom');
    transport.fetchDetail.mockRejectedValueOnce(err);

    controller.select('a');
    await flush();

    expect(onDetail).toHaveBeenCalledWith('a', undefined, err);
  });

  /** M5-COLD ticket Implement §2: "NO state/flow/error subscriptions while
   *  cold — selection shows descriptor only", asserted against the mock
   *  transport (ticket Tests: "FE: cold gating (no observe calls while cold —
   *  mock-transport assertion)"). Subscribing raises attention and can un-park
   *  a cone, so a graph the UI has just called parked must not be woken by
   *  looking at it. */
  describe('descriptor-only selection (cold graph)', () => {
    it('fetches the descriptor and issues no observe POST and no state fetch', async () => {
      controller.select('a', 'descriptor');
      await flush();

      expect(transport.fetchDetail).toHaveBeenCalledWith('a');
      expect(transport.observeStart).not.toHaveBeenCalled();
      expect(transport.fetchState).not.toHaveBeenCalled();
      expect(onState).not.toHaveBeenCalled();
    });

    it('moving between cells inside a cold graph never subscribes to any of them', async () => {
      controller.select('a', 'descriptor');
      await flush();
      controller.select('b', 'descriptor');
      await flush();

      expect(transport.observeStart).not.toHaveBeenCalled();
      // nothing was acquired, so nothing is released either
      expect(transport.observeStop).not.toHaveBeenCalled();
      expect(transport.fetchDetail).toHaveBeenNthCalledWith(2, 'b');
    });

    it('a state.summary for the selected ref does not pull its state in through the back door', async () => {
      controller.select('a', 'descriptor');
      await flush();

      controller.onSummary(summary('a'));
      await flush();

      expect(transport.fetchState).not.toHaveBeenCalled();
    });

    it('deselecting a descriptor-only selection issues no observe DELETE', async () => {
      controller.select('a', 'descriptor');
      await flush();
      controller.deselect();
      await flush();

      expect(transport.observeStop).not.toHaveBeenCalled();
    });

    /** The wake path: the same ref, now hot, must actually start observing —
     *  and the going-cold direction must release what it had. */
    it('re-selecting the same ref as live after a wake opens exactly one observation', async () => {
      controller.select('a', 'descriptor');
      await flush();

      controller.select('a', 'live');
      await flush();

      expect(transport.observeStart).toHaveBeenCalledTimes(1);
      expect(transport.observeStart).toHaveBeenCalledWith('a');
      expect(transport.fetchState).toHaveBeenCalledWith('a');
    });

    it('a live selection that goes cold releases its observation', async () => {
      controller.select('a', 'live');
      await flush();

      controller.select('a', 'descriptor');
      await flush();

      expect(transport.observeStop).toHaveBeenCalledTimes(1);
      expect(transport.observeStop).toHaveBeenCalledWith('a');
      expect(transport.observeStart).toHaveBeenCalledTimes(1);
    });
  });
});

/** Flush the microtask queue so chained `.then()`s in DetailController settle. */
async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}
