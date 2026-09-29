import { afterEach, describe, expect, it, vi } from 'vitest';
import { postOverride, postPause, postQuestion } from '../src/api/commands';

afterEach(() => vi.unstubAllGlobals());

describe('HTTP command contract', () => {
  it('posts a form-encoded question and returns its root ref', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ root: 'claim:1' }), { status: 200 }));
    vi.stubGlobal('fetch', fetch);

    await expect(postQuestion('Cars & cities?')).resolves.toBe('claim:1');
    expect(fetch).toHaveBeenCalledWith('/question', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'text=Cars+%26+cities%3F',
    });
  });

  it('posts the exact override fields', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('ok', { status: 200 }));
    vi.stubGlobal('fetch', fetch);

    await postOverride('claim:2', 'EXPAND');
    expect(fetch).toHaveBeenCalledWith('/override', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'id=claim%3A2&mode=EXPAND',
    });
  });

  it('posts the exact pause fields (CTL-05)', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('ok', { status: 200 }));
    vi.stubGlobal('fetch', fetch);

    await postPause('q:1', true);
    expect(fetch).toHaveBeenCalledWith('/question/pause', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'root=q%3A1&paused=true',
    });
    await postPause('q:1', false);
    expect(fetch).toHaveBeenLastCalledWith('/question/pause', expect.objectContaining({ body: 'root=q%3A1&paused=false' }));
  });

  it('rejects failed commands with the response status and body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('no such claim', { status: 404 })));
    await expect(postOverride('missing', 'STOP')).rejects.toThrow('/override failed: 404 no such claim');
  });
});
