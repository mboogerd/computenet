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
});

/** Flush the microtask queue so chained `.then()`s in DetailController settle. */
async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}
