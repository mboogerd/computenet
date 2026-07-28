import { createSignal } from 'solid-js';

/** Dark + light theme (M0-FE Implement §5: "dark + light theme via
 *  prefers-color-scheme"). tokens.css already switches on the OS media
 *  query with no JS involved; this adds a persisted manual override —
 *  [data-theme] on <html> wins over the media query either way — so a
 *  screenshot/manual check does not depend on being able to flip the OS
 *  theme (agora/ui precedent: src/solid/prefs.ts). */

const storedTheme = localStorage.getItem('inspect.theme');
const initialTheme: 'light' | 'dark' =
  storedTheme === 'light' || storedTheme === 'dark'
    ? storedTheme
    : matchMedia('(prefers-color-scheme: dark)').matches
      ? 'dark'
      : 'light';

const [theme, setThemeSig] = createSignal<'light' | 'dark'>(initialTheme);
export { theme };

export function toggleTheme(): void {
  const t = theme() === 'dark' ? 'light' : 'dark';
  setThemeSig(t);
  localStorage.setItem('inspect.theme', t);
  document.documentElement.setAttribute('data-theme', t);
}

/** Apply the persisted preference to <html> once at startup. */
export function initTheme(): void {
  document.documentElement.setAttribute('data-theme', theme());
}
