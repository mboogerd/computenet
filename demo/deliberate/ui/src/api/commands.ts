import type { Override, QuestionCreated } from './types';

async function postForm(path: string, fields: Record<string, string>): Promise<Response> {
  const res = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields).toString(),
  });
  if (!res.ok) throw new Error(`${path} failed: ${res.status} ${await res.text().catch(() => '')}`.trim());
  return res;
}

/** POST /question text=… → the new root claim ref. */
export async function postQuestion(text: string): Promise<string> {
  const res = await postForm('/question', { text });
  return ((await res.json()) as QuestionCreated).root;
}

/** CTL-05: POST /question/pause root=…&paused=true|false → "ok". */
export async function postPause(root: string, paused: boolean): Promise<void> {
  await postForm('/question/pause', { root, paused: String(paused) });
}

/** POST /override id=…&mode=… → "ok". */
export async function postOverride(id: string, mode: Override): Promise<void> {
  await postForm('/override', { id, mode });
}
