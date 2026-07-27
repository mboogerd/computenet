import { describe, expect, it } from 'vitest';
import { colorGlyph, manifestBadge, shortType } from '../src/util/badges';

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
