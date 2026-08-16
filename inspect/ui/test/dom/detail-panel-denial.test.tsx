/** @vitest-environment jsdom */
import { render, waitFor } from '@solidjs/testing-library';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import type { DeadLetterEntry } from '../../src/api/types';
import DetailPanel from '../../src/components/DetailPanel';
import { onErrorDeadLetter } from '../../src/solid/errors';
import { setSelection } from '../../src/solid/selection';
import { MATCHES_REF, resetAppState, startApp } from './harness';

/** computenet-4ixu: `DeadLetterRow.denial` (computenet-usd.7) reaches the
 *  wire but, before this ticket, `DeadLetterEntry` had no `denial` field and
 *  `DetailPanel` rendered a `BoundaryPolicy` refusal identically to a plain
 *  host-level drop — both `cause: null` — the exact defect usd.7 exists to
 *  close. This suite pushes a denial row as a live `error.deadLetter` event
 *  (`onErrorDeadLetter`, the same bridge a real SSE frame reaches — see
 *  `harness.tsx`'s module doc on calling the exported onStateSummary /
 *  onError-kind / onActivity bridges directly) rather than adding it to the shared
 *  `fixtures/errors.json` snapshot, so it carries none of that fixture's
 *  cross-suite invariants (every row resolves to a topology node,
 *  `counters.deadLetters === deadLetters.length`, the toggle-bar per-node
 *  badge count — see `errors-fixture.test.ts` / `toggle-bar.test.tsx`). Its
 *  own file, not folded into `detail-panel.test.tsx`, because `errorStore` is
 *  a module-level singleton `resetAppState()` does not clear (see
 *  `harness.tsx`'s "Reset discipline") — a pushed row would otherwise leak
 *  into every later test in whichever file it ran in. */
describe('DetailPanel — BoundaryPolicy denial (computenet-4ixu)', () => {
  beforeAll(startApp);
  beforeEach(resetAppState);

  const denialEntry: DeadLetterEntry = {
    ref: MATCHES_REF,
    cause: null,
    description:
      "boundary denial at exposure 'feed' on matches:0: seam=DISCLOSURE, reason=DISCLOSURE_DENIED, " +
      'principal=peer-1, subject=- — refused by BoundaryPolicy (spec 40/43); a denial is not a cell fault, ' +
      'no supervision policy was consulted and no wave was minted or advanced.',
    wave: null,
    atMs: 1753600600000,
    invocation: {
      port: 'feed',
      type: 'PORT_API',
      method: 'DISCLOSURE',
      parameterTypes: [],
      argCount: 1,
      hop: null,
    },
    disposition: [],
    denial: {
      seam: 'DISCLOSURE',
      reason: 'DISCLOSURE_DENIED',
      exposure: 'feed',
      principal: 'peer-1',
      subject: null,
      detail: null,
    },
  };

  it('renders a refusal distinguishably from the fixture fault card for the same ref, using the structural field', async () => {
    const { container, getByText } = render(() => <DetailPanel />);
    setSelection(MATCHES_REF);

    // fixtures/errors.json's pre-existing thrown-fault card for this ref.
    await waitFor(() => expect(getByText('OwnershipViolation')).toBeTruthy());

    onErrorDeadLetter(denialEntry);
    await waitFor(() => expect(container.textContent).toMatch(/DISCLOSURE_DENIED/));

    const cards = [...container.querySelectorAll('.dead-letter-card')];
    expect(cards).toHaveLength(2);

    const denialCard = cards.find((c) => c.textContent?.includes('DISCLOSURE_DENIED'));
    const faultCard = cards.find((c) => c.textContent?.includes('OwnershipViolation'));
    expect(denialCard).toBeTruthy();
    expect(faultCard).toBeTruthy();

    // never falls back to the plain-drop wording for a denial
    expect(denialCard!.textContent).not.toContain('dropped (unknown target)');

    // visually distinct: the denial header does not carry the fault's
    // `--error` (alarm-red) styling the way the fault card's cause line does.
    const denialCause = denialCard!.querySelector('.dead-letter-card__cause') as HTMLElement;
    const faultCause = faultCard!.querySelector('.dead-letter-card__cause') as HTMLElement;
    expect(denialCause.getAttribute('style') ?? '').toMatch(/wave-health/);
    expect(faultCause.getAttribute('style') ?? '').not.toMatch(/wave-health/);

    // the refusal's own attribution (seam/exposure) is rendered, not just the
    // free-text description a client previously had to parse.
    expect(denialCard!.textContent).toContain('exposure feed');
  });
});
