import type { CellDetail, Node } from '../api/types';

/** The one sentence the detail panel shows in State / Flow / Errors for a
 *  peer-hosted cell (M5-NET Exclusions, verbatim). Kept as a constant so all
 *  three subsections say exactly the same thing. */
export const REMOTE_NOTICE = 'remote — state/flow/errors not available in this milestone';

/**
 * Is this cell hosted on another JVM?
 *
 * `host === null` is the discriminator, and it is the server's own statement
 * rather than a guess: `Node.host` is the process host a `LocationRegistry`
 * *located*, which it can only do for a cell published on one of its own
 * `ManagedHost`s. A peer-announced ref has a mirrored location — a bridge, not
 * a host — so the contract's `host` is null for it and non-null for every
 * locally published cell (M5-NET Implement §1: "Process-host attribution for
 * remote cells may be unavailable — `host: null` is acceptable").
 *
 * Deliberately not "net !== 'local'": the local JVM's network host is
 * whatever the launcher's `--net-name` says, so the client cannot recognise it
 * by value.
 */
export function isRemotePlacement(node: Pick<Node, 'host'> | CellDetail | null | undefined): boolean {
  return !!node && node.host === null;
}
