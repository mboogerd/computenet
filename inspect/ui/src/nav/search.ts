import type { SearchMode } from '../api/types';

export interface SearchModeOption {
  readonly mode: SearchMode;
  readonly label: string;
  readonly enabled: boolean;
  /** Shown as the disabled chip's tooltip (M4-FE ticket Implement §4:
   *  "Data: chip disabled with tooltip 'arrives in M5'"). Absent for an
   *  enabled mode. */
  readonly disabledReason?: string;
}

/** 10-target-v3.md Navigator: "Search with modes: name (live filter),
 *  problems (...), data (M5 — find the cell holding a record)"; M4-FE
 *  ticket Implement §4. `data` stays disabled until M5 even though the
 *  contract already names the endpoint mode — the BE itself returns 501 for
 *  it (M4-BE ticket Implement §4), so gating the chip here is a UX
 *  courtesy, not a workaround for a missing server capability. */
export const SEARCH_MODES: readonly SearchModeOption[] = [
  { mode: 'name', label: 'Name', enabled: true },
  { mode: 'problems', label: 'Problems', enabled: true },
  { mode: 'data', label: 'Data', enabled: false, disabledReason: 'arrives in M5' },
];

export function isSearchModeEnabled(mode: SearchMode): boolean {
  return SEARCH_MODES.some((m) => m.mode === mode && m.enabled);
}
