import { createSignal } from 'solid-js';

/** The five overlay toggles (10-target-v3.md "The v3 model"). "Process
 *  hosts" and "State" became functional in M1 (M1-FE ticket Implement §3 +
 *  the "Correction for clarity" note); "Errors" becomes functional in M2
 *  (M2-FE ticket Implement §2); "Flow" becomes functional in M3 (M3-FE
 *  ticket Implement §2). "Network hosts" (M5) stays disabled — `ToggleBar`
 *  still renders all five, per the M0-FE precedent, so the toggle set
 *  itself never changes shape across milestones. */
export const [showHosts, setShowHosts] = createSignal(false);
export const [showState, setShowState] = createSignal(false);
export const [showErrors, setShowErrors] = createSignal(false);
export const [showFlow, setShowFlow] = createSignal(false);
