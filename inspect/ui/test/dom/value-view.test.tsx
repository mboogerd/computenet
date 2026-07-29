/** @vitest-environment jsdom */
import { cleanup, render } from '@solidjs/testing-library';
import { afterEach, describe, expect, it } from 'vitest';
import type { Value } from '../../src/api/types';
import ValueView from '../../src/components/ValueView';
import cellStateOpaque from '../../fixtures/cell-state-opaque.json';
import cellStateScalar from '../../fixtures/cell-state-scalar.json';
import cellStateTable from '../../fixtures/cell-state-table.json';
import cellStateTree from '../../fixtures/cell-state-tree.json';
import cellStateTruncated from '../../fixtures/cell-state-truncated.json';

/** ValueView.tsx: a pure component over the contract's `Value` shape
 *  (20-api-contract.md "Value") — rendered directly with a prop value, per
 *  the ticket's own instruction for plain-props components, skipping the
 *  DOM harness entirely (no fetch/EventSource involved). `cleanup()` is
 *  called explicitly here (not relying on harness.tsx, which this file does
 *  not import) since @solidjs/testing-library does not auto-cleanup between
 *  `it()`s on its own. */
afterEach(() => cleanup());

describe('ValueView', () => {
  it('$table: renders columns and rows', () => {
    const { container } = render(() => <ValueView value={cellStateTable.value as Value} />);
    const headers = [...container.querySelectorAll('.value-view__table thead th')].map((h) => h.textContent);
    expect(headers).toEqual(['skill']);
    const rows = [...container.querySelectorAll('.value-view__table tbody tr')].map((r) => r.textContent);
    expect(rows).toEqual(['Kotlin', 'TypeScript']);
  });

  it('$table: marks a tombstoned row (the {cells, tombstoned: true} row shape api/types.ts still allows)', () => {
    // No checked-in fixture carries this shape — `api/types.ts`'s own doc
    // comment on `TableRow` says the server has never constructed one
    // ("dead code today"). Editing a fixture to add one would be exactly
    // the "fixture edited to suit a frontend test" the ticket forbids
    // (`FixtureContractTest` owns fixture shape); constructing the literal
    // `Value` inline, as a plain prop to this pure component, is not a
    // fixture edit — it exercises a real branch of `isTombstoneRow`/
    // `rowCells` (api/types.ts) that would otherwise have zero DOM coverage.
    const value: Value = {
      $table: {
        columns: ['skill'],
        rows: [['Kotlin'], { cells: ['Rust'], tombstoned: true }],
      },
    };
    const { container } = render(() => <ValueView value={value} />);
    const rows = container.querySelectorAll('.value-view__table tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0]!.classList.contains('is-tombstoned')).toBe(false);
    expect(rows[1]!.classList.contains('is-tombstoned')).toBe(true);
    expect(rows[1]!.textContent).toBe('Rust');
  });

  it('$truncated: shows "showing N of M"', () => {
    const { container } = render(() => <ValueView value={cellStateTruncated.value as Value} />);
    expect(container.querySelector('.value-view__truncated')?.textContent).toBe('showing 200 of 1800');
    // the table itself still renders alongside the truncation note
    expect(container.querySelectorAll('.value-view__table tbody tr').length).toBe(3);
  });

  it('$opaque: shows the type and text', () => {
    const { container } = render(() => <ValueView value={cellStateOpaque.value as Value} />);
    const opaque = container.querySelector('.value-view__opaque');
    expect(opaque?.querySelector('.value-view__opaque-type')?.textContent).toBe(
      'civictech.demo.skillmatch.AdvertisedLedger',
    );
    expect(opaque?.querySelector('code')?.textContent).toBe('AdvertisedLedger@3f2c1a{entries=12, tombstones=2}');
  });

  it('scalar: renders the bare value', () => {
    const { container } = render(() => <ValueView value={cellStateScalar.value as Value} />);
    expect(container.querySelector('.value-view__scalar')?.textContent).toBe('42');
  });

  it('nested object/array tree: renders a tree with keys and list items', () => {
    const { container } = render(() => <ValueView value={cellStateTree.value as Value} />);
    const tree = container.querySelector('.value-view__tree') as HTMLElement;
    expect(tree).toBeTruthy();
    expect(tree.textContent).toContain('alice');
    expect(tree.textContent).toContain('Kotlin');
    expect(tree.textContent).toContain('Rust');
    expect(tree.textContent).toContain('bob');
    expect(tree.textContent).toContain('0.82');
    // nested list under alice.skills
    expect(container.querySelectorAll('.value-view__tree ul').length).toBeGreaterThan(1);
  });
});
