import type { JSX } from 'solid-js';

/** One inline icon set for the whole app (better-ui "one library per surface").
 *  24px grid, outline, `currentColor` so state comes from CSS color; strokes
 *  are non-scaling so the rendered width tracks the adjacent text weight
 *  (1.5px beside 400-weight text, 2px beside semibold labels). */
function Icon(props: { size?: number; stroke?: number; class?: string; children: JSX.Element }) {
  const s = () => props.size ?? 16;
  return (
    <svg
      class={`icon ${props.class ?? ''}`}
      width={s()}
      height={s()}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width={props.stroke ?? 1.5}
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      {props.children}
    </svg>
  );
}

type P = { size?: number; stroke?: number; class?: string };

export const SunIcon = (p: P) => (
  <Icon {...p}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41" />
  </Icon>
);

export const MoonIcon = (p: P) => (
  <Icon {...p}>
    <path d="M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401" />
  </Icon>
);

/** Motion on: a waveform. Motion off: the same baseline, flat. */
export const MotionOnIcon = (p: P) => (
  <Icon {...p}>
    <path d="M2 12h3l3-7 4 14 4-10 2 3h4" />
  </Icon>
);

export const MotionOffIcon = (p: P) => (
  <Icon {...p}>
    <path d="M2 12h20" />
  </Icon>
);

export const PlusIcon = (p: P) => (
  <Icon {...p}>
    <path d="M12 5v14M5 12h14" />
  </Icon>
);

export const CloseIcon = (p: P) => (
  <Icon {...p}>
    <path d="M18 6 6 18M6 6l12 12" />
  </Icon>
);

/** Attack: a blocked arrow (matches the map's ⊣ attack marker). */
export const AttackIcon = (p: P) => (
  <Icon {...p}>
    <path d="M3 12h13M19 6v12" />
  </Icon>
);

/** Support: an arrow into the claim (matches the map's ▶ support marker). */
export const SupportIcon = (p: P) => (
  <Icon {...p}>
    <path d="M3 12h14M13 7l5 5-5 5" />
  </Icon>
);

export const DebateIcon = (p: P) => (
  <Icon {...p}>
    <rect x="3" y="4" width="7.5" height="16" rx="2" />
    <rect x="13.5" y="4" width="7.5" height="16" rx="2" />
  </Icon>
);

export const MapIcon = (p: P) => (
  <Icon {...p}>
    <circle cx="6" cy="6" r="2.5" />
    <circle cx="18" cy="8" r="2.5" />
    <circle cx="10" cy="18" r="2.5" />
    <path d="M8.3 7.1 15.6 7.6M7 8.3l2.2 7.4M16.4 10l-4.6 6.3" />
  </Icon>
);

export const FitIcon = (p: P) => (
  <Icon {...p}>
    <path d="M4 9V5a1 1 0 0 1 1-1h4M15 4h4a1 1 0 0 1 1 1v4M20 15v4a1 1 0 0 1-1 1h-4M9 20H5a1 1 0 0 1-1-1v-4" />
  </Icon>
);

export const ZoomInIcon = (p: P) => (
  <Icon {...p}>
    <path d="M12 6v12M6 12h12" />
  </Icon>
);

export const ZoomOutIcon = (p: P) => (
  <Icon {...p}>
    <path d="M6 12h12" />
  </Icon>
);

export const AlertIcon = (p: P) => (
  <Icon {...p}>
    <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
    <path d="M12 9v4M12 17h.01" />
  </Icon>
);

export const ChevronIcon = (p: P) => (
  <Icon {...p}>
    <path d="m9 18 6-6-6-6" />
  </Icon>
);

export const TrashIcon = (p: P) => (
  <Icon {...p}>
    <path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
  </Icon>
);

/** Brand mark: three claims, one support and one attack link. Not an icon
 *  (filled, multi-color), so it lives outside the outline set's rules. */
export function AgoraMark(props: { size?: number }) {
  const s = () => props.size ?? 22;
  return (
    <svg class="agora-mark" width={s()} height={s()} viewBox="0 0 32 32" aria-hidden="true">
      <rect x="1" y="1" width="30" height="30" rx="9" fill="var(--text)" />
      <path d="M10 11 L16 21" stroke="var(--surface)" stroke-width="1.6" stroke-linecap="round" opacity="0.7" />
      <path d="M22 11 L16 21" stroke="var(--surface)" stroke-width="1.6" stroke-linecap="round" stroke-dasharray="2 2" opacity="0.7" />
      <circle cx="10" cy="10" r="3.4" fill="var(--accept-strong)" />
      <circle cx="22" cy="10" r="3.4" fill="var(--reject-strong)" />
      <circle cx="16" cy="22" r="4" fill="var(--surface)" />
    </svg>
  );
}
