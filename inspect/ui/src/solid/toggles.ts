import { createSignal } from 'solid-js';

/** The five overlay toggles (10-target-v3.md "The v3 model"). Only "Process
 *  hosts" and "State" are functional in M1 (M1-FE ticket Implement §3 + the
 *  "Correction for clarity" note); "Network hosts" (M5), "Flow" (M3) and
 *  "Errors" (M2) stay disabled — `ToggleBar` still renders all five, per the
 *  M0-FE precedent, so the toggle set itself never changes shape across
 *  milestones. */
export const [showHosts, setShowHosts] = createSignal(false);
export const [showState, setShowState] = createSignal(false);
