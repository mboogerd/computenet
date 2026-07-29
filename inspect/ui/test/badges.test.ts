import { describe, expect, it } from 'vitest';
import { capitalize, colorGlyph, manifestBadge, shortType } from '../src/util/badges';

describe('colorGlyph', () => {
  it('maps the three cell colors to their P/B/S glyph', () => {
    expect(colorGlyph('PURE')).toBe('P');
    expect(colorGlyph('BLOCKING')).toBe('B');
    expect(colorGlyph('SUSPENDING')).toBe('S');
  });

  it('falls back for an unknown/null color', () => {
    expect(colorGlyph(null)).toBe('?');
  });
});

describe('manifestBadge', () => {
  it('uses the ticket-named short letters for the four called-out manifests', () => {
    expect(manifestBadge('DURABLE')).toBe('D');
    expect(manifestBadge('GLITCH_FREE')).toBe('GF');
    expect(manifestBadge('REPLICATED')).toBe('R');
    expect(manifestBadge('PARTITIONED')).toBe('PT');
  });

  it('still renders a badge for a manifest not named by the ticket', () => {
    expect(manifestBadge('PULL_SERVING')).toBe('PS');
    expect(manifestBadge('GATED')).toBe('GA');
  });
});

describe('shortType', () => {
  it('takes the last dotted segment of a fully-qualified class name', () => {
    expect(shortType('civictech.cell.data.op.GroupByCell')).toBe('GroupByCell');
  });

  it('returns the whole string when there is no dot', () => {
    expect(shortType('GroupByCell')).toBe('GroupByCell');
  });
});

describe('capitalize', () => {
  it('uppercases the first letter of a lowercase server string', () => {
    expect(capitalize('focus')).toBe('Focus');
    expect(capitalize('idle')).toBe('Idle');
  });

  it('still works for an unrecognized future value, not just the two known bands', () => {
    expect(capitalize('deep-sleep')).toBe('Deep-sleep');
  });

  it('handles an empty string without throwing', () => {
    expect(capitalize('')).toBe('');
  });
});
