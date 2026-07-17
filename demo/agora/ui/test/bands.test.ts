import { describe, it, expect } from 'vitest';
import { bandFor } from '../src/styles/bands';

describe('bandFor', () => {
  it('maps each band boundary', () => {
    expect(bandFor(0.01)).toBe('reject-strong');
    expect(bandFor(0.19)).toBe('reject-strong');
    expect(bandFor(0.2)).toBe('reject-lean');
    expect(bandFor(0.39)).toBe('reject-lean');
    expect(bandFor(0.4)).toBe('contested');
    expect(bandFor(0.59)).toBe('contested');
    expect(bandFor(0.6)).toBe('accept-lean');
    expect(bandFor(0.79)).toBe('accept-lean');
    expect(bandFor(0.8)).toBe('accept-strong');
    expect(bandFor(0.99)).toBe('accept-strong');
  });
});
