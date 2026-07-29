import { describe, expect, it } from 'vitest';
import type { DeadLetterEntry, RestartEntry } from '../src/api/types';
import { buildSupervisionTimeline } from '../src/util/supervision';

function restart(over: Partial<RestartEntry> = {}): RestartEntry {
  return { ref: 'a:0', generation: 1, atMs: 1000, cause: null, causeAtMs: null, reBaselineAtMs: null, ...over };
}

function deadLetter(over: Partial<DeadLetterEntry> = {}): DeadLetterEntry {
  return {
    ref: 'a:0',
    cause: 'IllegalStateException',
    description: 'boom',
    wave: null,
    atMs: 1000,
    invocation: null,
    disposition: [],
    ...over,
  };
}

describe('buildSupervisionTimeline', () => {
  it('emits crash -> restart -> re-baseline in that order when all three are observed', () => {
    const r = restart({ generation: 2, atMs: 2000, cause: 'IllegalStateException', causeAtMs: 1000, reBaselineAtMs: 2100 });
    const steps = buildSupervisionTimeline([r], []);
    expect(steps.map((s) => s.kind)).toEqual(['crash', 'restart', 'reBaseline']);
    expect(steps[0].atMs).toBe(1000);
    expect(steps[1].atMs).toBe(2000);
    expect(steps[2].atMs).toBe(2100);
    expect(steps[0].label).toBe('crash — IllegalStateException');
    expect(steps[1].label).toBe('restart — generation 2');
    expect(steps[2].label).toBe('re-baseline');
  });

  it('omits the crash step when cause is null — never a negative claim, just absence', () => {
    const r = restart({ cause: null, causeAtMs: null, reBaselineAtMs: 2100 });
    const steps = buildSupervisionTimeline([r], []);
    expect(steps.map((s) => s.kind)).toEqual(['restart', 'reBaseline']);
  });

  it('omits the re-baseline step when reBaselineAtMs is null — never renders "no re-baseline"', () => {
    const r = restart({ cause: 'X', causeAtMs: 500, reBaselineAtMs: null });
    const steps = buildSupervisionTimeline([r], []);
    expect(steps.map((s) => s.kind)).toEqual(['crash', 'restart']);
    expect(steps.some((s) => /no re-baseline|did not/i.test(s.label))).toBe(false);
  });

  it('never omits the restart step itself, even with no cause and no re-baseline observed', () => {
    const r = restart({ cause: null, causeAtMs: null, reBaselineAtMs: null });
    const steps = buildSupervisionTimeline([r], []);
    expect(steps.map((s) => s.kind)).toEqual(['restart']);
  });

  it('orders multiple restarts newest-first, each contributing its own group', () => {
    const older = restart({ generation: 1, atMs: 1000, reBaselineAtMs: 1100 });
    const newer = restart({ generation: 2, atMs: 2000, reBaselineAtMs: 2100 });
    const steps = buildSupervisionTimeline([older, newer], []);
    // newer group (generation 2) entirely before the older group (generation 1)
    const restartSteps = steps.filter((s) => s.kind === 'restart');
    expect(restartSteps.map((s) => s.label)).toEqual(['restart — generation 2', 'restart — generation 1']);
  });

  it('attaches a matching dead letter description to the crash step as detail, never fabricating one', () => {
    const dl = deadLetter({ ref: 'a:0', cause: 'IllegalStateException', atMs: 1000, description: 'the real cause description' });
    const r = restart({ cause: 'IllegalStateException', causeAtMs: 1000, reBaselineAtMs: null });
    const steps = buildSupervisionTimeline([r], [dl]);
    const crash = steps.find((s) => s.kind === 'crash');
    expect(crash?.detail).toBe('the real cause description');
  });

  it('leaves detail null when no dead letter matches (ref/atMs/cause)', () => {
    const r = restart({ cause: 'IllegalStateException', causeAtMs: 1000, reBaselineAtMs: null });
    const steps = buildSupervisionTimeline([r], [deadLetter({ ref: 'a:0', cause: 'Other', atMs: 1000 })]);
    const crash = steps.find((s) => s.kind === 'crash');
    expect(crash?.detail).toBeNull();
  });

  it('returns [] for no restarts', () => {
    expect(buildSupervisionTimeline([], [])).toEqual([]);
  });

  it('treats cause/causeAtMs/reBaselineAtMs as absent (undefined), not just null, per the optional-tolerant-on-read contract', () => {
    // An older server may omit these fields entirely rather than sending explicit
    // nulls (see fixtures/error-event-restart.json, which has no cause/causeAtMs/
    // reBaselineAtMs keys at all). The builder must not fabricate a crash or
    // re-baseline step from `undefined`.
    const r = { ref: 'a:0', generation: 2, atMs: 1753600700000 } as unknown as RestartEntry;
    const steps = buildSupervisionTimeline([r], []);
    expect(steps.map((s) => s.kind)).toEqual(['restart']);
  });

  it('every step has a unique key', () => {
    const r1 = restart({ generation: 1, atMs: 1000, cause: 'X', causeAtMs: 900, reBaselineAtMs: 1100 });
    const r2 = restart({ generation: 2, atMs: 2000, cause: 'X', causeAtMs: 1900, reBaselineAtMs: 2100 });
    const steps = buildSupervisionTimeline([r1, r2], []);
    expect(new Set(steps.map((s) => s.key)).size).toBe(steps.length);
  });
});
