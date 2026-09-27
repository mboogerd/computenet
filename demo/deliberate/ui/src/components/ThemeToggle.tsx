import { createSignal, onCleanup, onMount } from 'solid-js';
import { applyTheme, loadTheme, nextTheme, resolvedTheme, type ThemePref } from '../util/theme';

export function ThemeToggle() {
  const [pref, setPref] = createSignal<ThemePref>('system');
  const [systemDark, setSystemDark] = createSignal(false);

  onMount(() => {
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    setSystemDark(mq.matches);
    const onChange = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    mq.addEventListener('change', onChange);
    onCleanup(() => mq.removeEventListener('change', onChange));
    const saved = loadTheme();
    setPref(saved);
    applyTheme(saved);
  });

  const shown = () => resolvedTheme(pref(), systemDark());
  const toggle = () => {
    const next = nextTheme(pref(), systemDark());
    setPref(next);
    applyTheme(next);
  };

  return (
    <button
      type="button"
      class="iconbtn theme"
      classList={{ 'is-dark': shown() === 'dark' }}
      aria-label={shown() === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
      title={shown() === 'dark' ? 'Light theme' : 'Dark theme'}
      onClick={toggle}
    >
      <span class="theme__glyph" aria-hidden="true" />
    </button>
  );
}
