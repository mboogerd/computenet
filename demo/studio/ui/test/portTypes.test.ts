import { describe, expect, it } from 'vitest';
import { renderToString } from 'solid-js/web';
import { createComponent } from 'solid-js';
import Wire from '../src/Wire';
import { compatible, portColor, portColors, requirementKind } from '../src/portTypes';

describe('derived port types', () => {
  it('assigns distinct colors and handles missing legacy metadata', () => {
    expect(new Set(Object.values(portColors)).size).toBe(6);
    expect(portColor()).toBe(portColors.any);
    expect(requirementKind({ fields: ['repo'], kind: null })).toBe('gitRange');
    expect(requirementKind({ fields: ['new'], kind: null })).toBe('text');
  });
  it('matches structural fields, nominal annotations, gate extras and deferred checks', () => {
    const repo = { fields: ['repo'], kind: null };
    expect(compatible({ kind: 'text' }, repo)).toBe(false);
    expect(compatible({ kind: 'gitRange' }, repo)).toBe(true);
    expect(compatible({ kind: 'json' }, repo)).toBe(true);
    expect(compatible({ kind: 'any' }, repo)).toBe(true);
    expect(compatible({ kind: 'gitRange' }, { fields: ['new'], kind: 'issueSet' })).toBe(false);
    expect(compatible({ kind: 'text', extras: ['gate'] }, { fields: ['gate'], kind: null })).toBe(true);
    expect(compatible({ kind: 'json', schema: { properties: { repo: {} } } }, { fields: ['repo'], kind: 'gitRange' })).toBe(false);
  });
});

describe('wire rendering', () => {
  it('renders quarantine class, dashed-red styling hook, reason and selectable hit target', () => {
    const html = renderToString(() => createComponent(Wire, {
      edge: { id: 'e', from: 'a', to: 'b', port: 'x', status: 'quarantined', reason: "x: requires field 'repo'" },
      selected: true, path: 'M 0 0', select() {}, remove() {},
    }));
    expect(html).toContain('wire quarantined selected');
    expect(html).toContain('<title>');
    expect(html).toContain('requires field');
    expect(html).toContain('wire-hit');
    expect(html).toContain('role="button"');
  });
  it('renders an ordinary wire without quarantine state', () => {
    const html = renderToString(() => createComponent(Wire, {
      edge: { id: 'e', from: 'a', to: 'b', port: 'x', status: 'ok', reason: null },
      selected: false, path: 'M 0 0', select() {}, remove() {},
    }));
    expect(html).not.toContain('quarantined');
    expect(html).toContain('Input x');
  });
});
