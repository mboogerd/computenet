import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

// WCAG 2.x contrast of the text tokens against every surface text sits on,
// read from tokens.css itself so a token change cannot silently regress it.
const css = readFileSync(new URL('../src/styles/tokens.css', import.meta.url), 'utf8');
const appCss = readFileSync(new URL('../src/styles/app.css', import.meta.url), 'utf8');

function block(selector: string): Record<string, string> {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) throw new Error(`no ${selector} block`);
  const body = css.slice(start, css.indexOf('}', start));
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*(#[0-9a-f]{6})\s*;/gi)].map((m) => [m[1], m[2].toLowerCase()]));
}

const channel = (v: number) => {
  const c = v / 255;
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
};
const luminance = (hex: string) => {
  const [r, g, b] = [1, 3, 5].map((i) => channel(parseInt(hex.slice(i, i + 2), 16)));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
export const contrast = (a: string, b: string) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

const light = block(':root');
const dark = block(':root[data-theme="dark"]');
const osDark = block(':root:not([data-theme="light"])');

const TEXT = ['text', 'text-2', 'text-3', 'pro-text', 'con-text'] as const;
const SURFACES = ['page', 'surface', 'surface-sunk'] as const;
const SPECIAL_TEXT = ['page', 'toast-text'] as const;

describe('text contrast (WCAG AA, 4.5:1)', () => {
  it('covers every token actually used as a text colour', () => {
    const used = [...appCss.matchAll(/^[ \t]*color:\s*var\(--([\w-]+)\)/gm)].map((m) => m[1]);
    expect(new Set(used)).toEqual(new Set([...TEXT, ...SPECIAL_TEXT]));
  });

  for (const [name, theme] of [['light', light], ['dark', dark]] as const) {
    it(`${name}: every text token on every surface`, () => {
      for (const t of TEXT)
        for (const s of SURFACES) {
          const r = contrast(theme[t], theme[s]);
          expect(r, `${name} ${t} on ${s}`).toBeGreaterThanOrEqual(4.5);
        }
      const toast = contrast(theme['toast-text'], theme.toast);
      expect(toast).toBeGreaterThanOrEqual(4.5);
      expect(contrast(theme.page, theme.text), `${name} inverse button text`).toBeGreaterThanOrEqual(4.5);
      expect(contrast(theme.text, theme['seg-on']), `${name} selected control text`).toBeGreaterThanOrEqual(4.5);
    });
  }

  it('the OS dark preference uses the same values as the dark toggle', () => {
    for (const t of [...TEXT, ...SURFACES, 'toast', 'toast-text']) expect(osDark[t], t).toBe(dark[t]);
  });

  it('keeps the bright pro/con values for fills, distinct from the darker light-theme text values', () => {
    expect(light.pro).not.toBe(light['pro-text']);
    expect(light.con).not.toBe(light['con-text']);
  });
});

function rule(selector: string): string {
  const start = appCss.indexOf(`${selector} {`);
  if (start < 0) throw new Error(`no ${selector} rule`);
  return appCss.slice(start, appCss.indexOf('}', start));
}

describe('visual CSS contracts', () => {
  it('draws the spread band and consensus marker above the soft fill', () => {
    expect(rule('.gauge__fill')).toContain('background: var(--fill-soft)');
    expect(rule('.gauge__band')).toContain('z-index: 1');
    expect(rule('.gauge__band')).toContain('background: var(--band)');
    expect(rule('.gauge__mark')).toContain('z-index: 2');
  });

  it('keeps the now line to one fixed-height row', () => {
    expect(rule('.now')).toContain('flex-wrap: nowrap');
    expect(rule('.now')).toContain('height: 20px');
    expect(rule('.now')).toContain('overflow: hidden');
    expect(rule('.now')).toContain('white-space: nowrap');
  });
});
