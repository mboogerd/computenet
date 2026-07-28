import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CellDetail, CellState } from '../src/api/types';
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

  it('onSummary(ref) refetches state only when ref matches the current selection', async () => {
    controller.select('a');
    await flush();
    onState.mockClear();
    transport.fetchState.mockClear();

    controller.onSummary('b'); // not selected — ignored
    await flush();
    expect(transport.fetchState).not.toHaveBeenCalled();

    controller.onSummary('a'); // selected — refetches
    await flush();
    expect(transport.fetchState).toHaveBeenCalledTimes(1);
    expect(transport.fetchState).toHaveBeenCalledWith('a');
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

      controller.onSummary('a');
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
