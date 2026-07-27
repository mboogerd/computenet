import { createLayeredLayout } from '../layout/layered';

/** One shared, persistent layout instance for the app's lifetime — its slot
 *  assignments must survive across recomputes (that persistence is exactly
 *  what makes insertion-stable layout possible; see layout/layered.ts). */
export const layoutEngine = createLayeredLayout();
