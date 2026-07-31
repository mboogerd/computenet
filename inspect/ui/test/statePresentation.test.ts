import { describe, expect, it } from 'vitest';
import { REMOTE_NOTICE } from '../src/util/placement';
import {
  caveatNote,
  exclusivesElidedLabel,
  isStaleProvenance,
  pageCounterText,
  provenanceLabel,
  unavailableMessage,
  walkStableNote,
} from '../src/util/statePresentation';

describe('provenanceLabel', () => {
  it('renders nothing for null/undefined — never defaulted to "live"', () => {
    expect(provenanceLabel(null)).toBeNull();
    expect(provenanceLabel(undefined)).toBeNull();
  });

  it('renders each known value distinctly', () => {
    expect(provenanceLabel('live')).toBe('live');
    expect(provenanceLabel('liveSuspended')).toMatch(/suspended/i);
    expect(provenanceLabel('liveSuspended')).not.toMatch(/warning|degraded|caution/i);
    expect(provenanceLabel('checkpoint')).toMatch(/checkpoint/i);
    expect(provenanceLabel('checkpoint')).toMatch(/not as of now|drain/i);
  });

  it('renders an unrecognized future value verbatim rather than crashing or going blank', () => {
    // @ts-expect-error — deliberately an out-of-union string, the forward-tolerance case
    expect(provenanceLabel('futureValue')).toBe('futureValue');
  });
});

describe('isStaleProvenance', () => {
  it('is true only for checkpoint', () => {
    expect(isStaleProvenance('checkpoint')).toBe(true);
    expect(isStaleProvenance('live')).toBe(false);
    expect(isStaleProvenance('liveSuspended')).toBe(false);
    expect(isStaleProvenance(null)).toBe(false);
  });
});

describe('unavailableMessage', () => {
  it('keeps the pre-V1C-BE sentence verbatim when reason is null/undefined', () => {
    expect(unavailableMessage(null)).toBe('State unavailable for this cell.');
    expect(unavailableMessage(undefined)).toBe('State unavailable for this cell.');
  });

  it('gives each known reason its own sentence', () => {
    expect(unavailableMessage('migrating')).toMatch(/migrat|repartition/i);
    expect(unavailableMessage('remote')).toBe(REMOTE_NOTICE);
    expect(unavailableMessage('notStateful')).toMatch(/no readable state/i);
    expect(unavailableMessage('unanswered')).toMatch(/retry/i);
    expect(unavailableMessage('unknown')).toMatch(/does not recognize/i);
  });

  /** C10: the two reasons the SHIPPED backend mints that the draft contract
   *  this ticket was written against did not list. Without their own entries
   *  they fell through to the raw-reason fallback — truthful, but saying
   *  nothing about either, and in particular not saying that neither is
   *  worth retrying the way `unanswered` is. */
  it('covers the two shipped reasons the draft contract omitted', () => {
    expect(unavailableMessage('terminated')).toMatch(/terminated|dead host/i);
    expect(unavailableMessage('terminated')).not.toContain('reason: terminated');
    expect(unavailableMessage('readFailed')).toMatch(/broken cell|threw/i);
    expect(unavailableMessage('readFailed')).not.toContain('reason: readFailed');
  });

  it('renders a future reason string truthfully rather than crashing or going blank', () => {
    expect(unavailableMessage('somethingNewV5')).toContain('somethingNewV5');
  });
});

describe('exclusivesElidedLabel', () => {
  it('is an ownership fact, never truncation phrasing', () => {
    const label = exclusivesElidedLabel(3);
    expect(label).toContain('3 entries hold exclusive values');
    expect(label).not.toMatch(/showing \d+ of \d+/);
    expect(label).not.toMatch(/load more/i);
  });

  it('pluralizes for one', () => {
    expect(exclusivesElidedLabel(1)).toContain('1 entry holds exclusive values');
  });
});

describe('walkStableNote', () => {
  it('renders nothing for true or null — null is not a false', () => {
    expect(walkStableNote(true)).toBeNull();
    expect(walkStableNote(null)).toBeNull();
  });

  it('renders a smeared-read note only for false', () => {
    expect(walkStableNote(false)).toMatch(/smeared/i);
  });
});

/** C10: `page.caveats` is a SHIPPED field the draft contract omitted, so
 *  these render decisions were made against `Dto.kt`'s `StatePageView`, not
 *  against the ticket's prose. */
describe('caveatNote', () => {
  it('renders nothing for staleFrontier — walkStable: null already says it, once', () => {
    expect(caveatNote('staleFrontier')).toBeNull();
  });

  it('renders positionalCursor as a coverage weakening, so "complete" is not read as exact', () => {
    const note = caveatNote('positionalCursor')!;
    expect(note).toMatch(/positional/i);
    expect(note).toMatch(/skipped|twice|best-effort/i);
  });

  it('renders an unrecognized future caveat rather than dropping it', () => {
    expect(caveatNote('someFutureWeakening')).toContain('someFutureWeakening');
  });
});

describe('pageCounterText', () => {
  it('never invents a total — states only pages/entries fetched', () => {
    expect(pageCounterText(200, true)).toBe('200 entries loaded — more available');
    expect(pageCounterText(200, false)).toBe('200 entries — complete');
    expect(pageCounterText(200, true)).not.toMatch(/showing \d+ of \d+/);
  });

  it('pluralizes for a single entry', () => {
    expect(pageCounterText(1, false)).toBe('1 entry — complete');
  });
});
