import type { JSX } from 'solid-js';

/** Inline SVG icons — one outline asset per glyph, recoloured through
 *  `currentColor` (better-ui "One SVG, recolored per state"), 24px grid,
 *  1.5px stroke to match the app's 400-weight body text (better-ui icons.md).
 *  Decorative by default (`aria-hidden`): every button that carries one also
 *  carries a text label or an `aria-label`. No icon library — a handful of
 *  paths does not justify a dependency. */

type IconProps = { class?: string; filled?: boolean };

function Svg(props: IconProps & { children: JSX.Element }) {
  return (
    <svg
      class={`icon${props.class ? ` ${props.class}` : ''}`}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.5"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      {props.children}
    </svg>
  );
}

/** Product mark: three cells and the links between them. */
export function LogoIcon(props: IconProps) {
  return (
    <Svg class={props.class}>
      <path d="M7.5 8.5 16.5 6M7.5 9.5l9 7" />
      <circle cx="5.5" cy="9" r="2.5" fill="currentColor" stroke="none" />
      <circle cx="18.5" cy="5.5" r="2.5" />
      <circle cx="18.5" cy="17.5" r="2.5" />
    </Svg>
  );
}

/** Outline by default; `filled` marks the active (pinned) state. */
export function PinIcon(props: IconProps) {
  return (
    <Svg class={props.class}>
      <path
        d="M9 4h6l-1 5 3 3v2H7v-2l3-3-1-5Z"
        fill={props.filled ? 'currentColor' : 'none'}
      />
      <path d="M12 14v6" />
    </Svg>
  );
}

export function SunIcon(props: IconProps) {
  return (
    <Svg class={props.class}>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2.5v2M12 19.5v2M4.5 12h-2M21.5 12h-2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4" />
    </Svg>
  );
}

export function MoonIcon(props: IconProps) {
  return (
    <Svg class={props.class}>
      <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z" />
    </Svg>
  );
}

export function ChevronLeftIcon(props: IconProps) {
  return (
    <Svg class={props.class}>
      <path d="m14.5 6-6 6 6 6" />
    </Svg>
  );
}

/** Empty canvas / detail placeholder: a node with a cursor over it. */
export function SelectIcon(props: IconProps) {
  return (
    <Svg class={props.class}>
      <rect x="3" y="4" width="12" height="9" rx="2" />
      <path d="m13 12 7.5 3-3 1.5-1.5 3-3-7.5Z" />
    </Svg>
  );
}

/** Lost connection. */
export function OfflineIcon(props: IconProps) {
  return (
    <Svg class={props.class}>
      <path d="M2.5 8.5a14 14 0 0 1 5-3M21.5 8.5a14 14 0 0 0-9.5-3.5M5.5 12a9.5 9.5 0 0 1 3-2M18.5 12a9.5 9.5 0 0 0-2.5-1.7M9 15.5a4.5 4.5 0 0 1 6 0" />
      <circle cx="12" cy="19" r="0.75" fill="currentColor" />
      <path d="m3 3 18 18" />
    </Svg>
  );
}

export function CloseIcon(props: IconProps) {
  return (
    <Svg class={props.class}>
      <path d="M6 6l12 12M18 6 6 18" />
    </Svg>
  );
}
