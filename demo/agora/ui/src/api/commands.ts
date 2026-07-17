import type { Polarity, Ref } from './types';

const USER_KEY = 'agora.user';

/** A random client id, persisted (localStorage, not session) so "your stance"
 *  survives a reload. No identity model on the backend — this is just a label. */
export function userId(): string {
  let u = localStorage.getItem(USER_KEY);
  if (!u) {
    u = Math.random().toString(36).slice(2, 10);
    localStorage.setItem(USER_KEY, u);
  }
  return u;
}

/** POST /op parses `k=v&k=v` form encoding ONLY — never send JSON. Throws the
 *  backend's plain-text 400 body so the caller can toast it. No optimism. */
async function op(params: Record<string, string>): Promise<Response> {
  const res = await fetch('/op', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params),
  });
  if (!res.ok) throw new Error((await res.text()) || `op failed: ${res.status}`);
  return res;
}

export async function createClaim(text: string): Promise<{ ref: Ref }> {
  return (await op({ action: 'claim', text })).json();
}

export async function createEdge(
  source: Ref,
  target: Ref,
  polarity: Polarity,
): Promise<{ ref: Ref }> {
  return (await op({ action: 'edge', source, target, polarity })).json();
}

/** value=null clears this user's stance (sends a blank value). */
export async function setStance(ref: Ref, value: number | null): Promise<void> {
  await op({ action: 'stance', id: ref, user: userId(), value: value === null ? '' : String(value) });
}

export async function remove(ref: Ref): Promise<void> {
  await op({ action: 'remove', id: ref });
}
