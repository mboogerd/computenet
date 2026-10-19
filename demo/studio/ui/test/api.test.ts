import { afterEach, describe, expect, it, vi } from 'vitest';
import { request } from '../src/api';

afterEach(() => vi.unstubAllGlobals());
describe('JSON API requests', () => {
  it.each(['POST', 'PATCH'])('always sends JSON for %s, including empty actions', async method => {
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetcher);
    await request('/api/workflows/abcdefgh/nodes/n1/fire', method);
    expect(fetcher).toHaveBeenCalledWith('/api/workflows/abcdefgh/nodes/n1/fire', { method, headers: { 'Content-Type': 'application/json' }, body: '{}' });
  });
  it('preserves the kernel refusal error verbatim', async () => {
    const message = 'Link refused: n1 → n2 would close a cycle.';
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ error: message }), { status: 409 })));
    await expect(request('/api/workflows/abcdefgh/edges', 'POST', { from: 'n1', to: 'n2', port: 'in' })).rejects.toThrow(message);
  });
  it('parses JSON responses and accepts bodyless deletion', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response('{"nodes":[]}')).mockResolvedValueOnce(new Response(null, { status: 204 })));
    await expect(request('/api/workflows/abcdefgh/graph')).resolves.toEqual({ nodes: [] });
    await expect(request('/api/workflows/abcdefgh/nodes/n1', 'DELETE')).resolves.toBeUndefined();
  });
});
