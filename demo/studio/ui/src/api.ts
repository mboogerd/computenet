export async function request<T = void>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const json = method === 'POST' || method === 'PATCH';
  const response = await fetch(path, {
    method,
    ...(json ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) } : {}),
  });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try { message = (await response.json()).error ?? message; } catch { /* Preserve HTTP error. */ }
    throw new Error(message);
  }
  return response.status === 204 ? undefined as T : response.json();
}
export const nodePath = (id: string) => `/api/nodes/${encodeURIComponent(id)}`;
