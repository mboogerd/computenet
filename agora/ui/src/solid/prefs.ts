import { createSignal } from 'solid-js';

/** Theme + reduce-motion, persisted, applied as data-* on <html> (tokens.css
 *  keys off [data-theme] and [data-motion]). */

const storedTheme = localStorage.getItem('agora.theme');
const initialTheme: 'light' | 'dark' =
  storedTheme === 'light' || storedTheme === 'dark'
    ? storedTheme
    : matchMedia('(prefers-color-scheme: dark)').matches
      ? 'dark'
      : 'light';

const [theme, setThemeSig] = createSignal<'light' | 'dark'>(initialTheme);
const [motion, setMotionSig] = createSignal(localStorage.getItem('agora.motion') !== 'off');

export { theme, motion };

export function toggleTheme(): void {
  const t = theme() === 'dark' ? 'light' : 'dark';
  setThemeSig(t);
  localStorage.setItem('agora.theme', t);
  document.documentElement.setAttribute('data-theme', t);
}

export function toggleMotion(): void {
  const on = !motion();
  setMotionSig(on);
  localStorage.setItem('agora.motion', on ? 'on' : 'off');
  if (on) document.documentElement.removeAttribute('data-motion');
  else document.documentElement.setAttribute('data-motion', 'off');
}

/** Apply the persisted prefs to <html> once at startup. */
export function initPrefs(): void {
  document.documentElement.setAttribute('data-theme', theme());
  if (!motion()) document.documentElement.setAttribute('data-motion', 'off');
}
