/** Theme preference: follow the OS, or pin light/dark via `data-theme` on <html>. */
export type ThemePref = 'system' | 'light' | 'dark';

const KEY = 'deliberate.theme';

/** Toggle order. From `system` the first click goes to the opposite of what the OS shows. */
export function nextTheme(current: ThemePref, systemDark: boolean): ThemePref {
  if (current === 'system') return systemDark ? 'light' : 'dark';
  if (current === 'light') return systemDark ? 'system' : 'dark';
  return systemDark ? 'light' : 'system';
}

/** Effective scheme shown for a preference. */
export const resolvedTheme = (pref: ThemePref, systemDark: boolean): 'light' | 'dark' =>
  pref === 'system' ? (systemDark ? 'dark' : 'light') : pref;

export function loadTheme(): ThemePref {
  try {
    const v = window.localStorage.getItem(KEY);
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch {
    return 'system';
  }
}

/**
 * Runs `swap` with every CSS transition switched off, so a theme change lands
 * in one frame instead of every colour transition firing at once (better-ui:
 * suppress transitions on theme switch). The override is removed only after
 * the next paint, once the new colours have committed.
 */
export function withoutTransitions(swap: () => void): void {
  if (typeof document === 'undefined' || typeof requestAnimationFrame === 'undefined') {
    swap();
    return;
  }
  const style = document.createElement('style');
  style.append(document.createTextNode('*,*::before,*::after{transition:none !important}'));
  document.head.append(style);
  swap();
  void document.body.offsetHeight; // force a synchronous style flush
  requestAnimationFrame(() => requestAnimationFrame(() => style.remove()));
}

export function applyTheme(pref: ThemePref): void {
  const root = document.documentElement;
  withoutTransitions(() => {
    if (pref === 'system') delete root.dataset.theme;
    else root.dataset.theme = pref;
  });
  try {
    if (pref === 'system') window.localStorage.removeItem(KEY);
    else window.localStorage.setItem(KEY, pref);
  } catch {
    /* storage unavailable: the choice lasts for this page only */
  }
}
