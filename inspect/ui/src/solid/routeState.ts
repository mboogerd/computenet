import { createSignal } from 'solid-js';
import { parseHash } from '../nav/route';

/** The raw `screen`/`currentGraphId` signals, in their own leaf module with
 *  no dependency on `./state` or `./route` — the same cycle-avoidance split
 *  `./selection` already uses (see that file's own doc comment): both
 *  `solid/state.ts` (delta filtering by the current graph) and
 *  `solid/route.ts` (the navigation controller, which also needs
 *  `solid/state.ts`'s `setGraphFilter`) need to read these, and a module
 *  that both of *those* depend on cannot itself depend on either. */
export type Screen = 'home' | 'graph';

const initialRoute = parseHash(location.hash);

const [screen, setScreen] = createSignal<Screen>(initialRoute.screen);
const [currentGraphId, setCurrentGraphId] = createSignal<string | null>(
  initialRoute.screen === 'graph' ? initialRoute.graphId : null,
);

export { currentGraphId, screen, setCurrentGraphId, setScreen };
/** The one-time parsed boot hash — consumed exactly once by
 *  `solid/route.ts`'s `initRoute()` to restore selection/toggles, kept here
 *  so it and this module's own initial signal values agree on a single
 *  parse of `location.hash`. */
export { initialRoute };
