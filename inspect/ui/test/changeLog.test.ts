import { describe, expect, it, vi } from 'vitest';
import type { StateSummaryPayload } from '../src/api/types';
import { ChangeLog, MAX_CHANGE_LOG_ENTRIES } from '../src/sync/changeLog';

function payload(overrides: Partial<StateSummaryPayload> = {}): StateSummaryPayload {
  return {
    ref: 'a',
    cardinality: '3 rows',
    frontier: { source: 'host0000', counter: 3 },
    staleMs: 0,
    ...overrides,
  };
}

describe('ChangeLog', () => {
  it('appends an entry for the first summary seen (prev undefined -> indicatesChange true)', () => {
    const log = new ChangeLog();
    log.onSummary(payload());
    expect(log.entries).toHaveLength(1);
    expect(log.entries[0].cardinality).toBe('3 rows');
    expect(log.entries[0].frontier).toEqual({ source: 'host0000', counter: 3 });
  });

  it('does not append for a quiet window (same frontier/cardinality, staleMs only grows)', () => {
    const log = new ChangeLog();
    log.onSummary(payload({ staleMs: 0 }));
    log.onSummary(payload({ staleMs: 1000 }));
    log.onSummary(payload({ staleMs: 2000 }));
    expect(log.entries).toHaveLength(1);
  });

  it('appends again once a summary indicates an effective change', () => {
    const log = new ChangeLog();
    log.onSummary(payload({ staleMs: 0, cardinality: '3 rows' }));
    log.onSummary(payload({ staleMs: 1000, cardinality: '3 rows' })); // quiet
    log.onSummary(payload({ staleMs: 0, cardinality: '4 rows', frontier: { source: 'host0000', counter: 4 } })); // changed
    expect(log.entries).toHaveLength(2);
  });

  it('is bounded to the last 50 entries', () => {
    const log = new ChangeLog();
    for (let i = 0; i < MAX_CHANGE_LOG_ENTRIES + 10; i++) {
      // every summary here indicates change: a distinct frontier each time
      log.onSummary(payload({ frontier: { source: 'host0000', counter: i } }));
    }
    expect(log.entries).toHaveLength(MAX_CHANGE_LOG_ENTRIES);
  });

  it('reads newest first', () => {
    const log = new ChangeLog();
    log.onSummary(payload({ frontier: { source: 'host0000', counter: 1 }, cardinality: '1 rows' }));
    log.onSummary(payload({ frontier: { source: 'host0000', counter: 2 }, cardinality: '2 rows' }));
    expect(log.entries[0].cardinality).toBe('2 rows');
    expect(log.entries[1].cardinality).toBe('1 rows');
  });

  it('clear() empties the log and resets the change baseline, so the next summary after clear always appends', () => {
    const log = new ChangeLog();
    log.onSummary(payload());
    log.clear();
    expect(log.entries).toHaveLength(0);

    // Even an identical-looking payload counts as a change post-clear —
    // the log is per selected cell and does not survive selecting a
    // different one (ticket): there is no "last seen" to compare against.
    log.onSummary(payload());
    expect(log.entries).toHaveLength(1);
  });

  it('notifies subscribers only on an actual mutation (append or non-empty clear)', () => {
    const log = new ChangeLog();
    const fn = vi.fn();
    log.subscribe(fn);

    log.onSummary(payload({ staleMs: 0 })); // append -> notify
    log.onSummary(payload({ staleMs: 1000 })); // quiet -> no notify
    expect(fn).toHaveBeenCalledTimes(1);

    log.clear(); // non-empty -> notify
    expect(fn).toHaveBeenCalledTimes(2);

    log.clear(); // already empty -> no notify
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('an unsubscribed callback stops receiving notifications', () => {
    const log = new ChangeLog();
    const fn = vi.fn();
    const unsubscribe = log.subscribe(fn);
    unsubscribe();

    log.onSummary(payload());
    expect(fn).not.toHaveBeenCalled();
  });
});
