import { postOverride, postQuestion } from '../api/commands';
import type { GraphDto } from '../api/types';
import type { ConnState, GraphSource } from './source';

/** Live source: GET /graph once for the first paint, then /events, where every
 *  frame is a full GraphDto. EventSource retries on its own for transient
 *  errors; when it gives up (readyState CLOSED, e.g. the backend restarted and
 *  answered with a non-200) we reopen it ourselves with capped backoff. */
export class LiveSource implements GraphSource {
  private es?: EventSource;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private backoff = 1000;
  private stopped = false;
  private sawFrame = false;

  start(onGraph: (g: GraphDto) => void, onState: (s: ConnState) => void): void {
    this.stopped = false;
    void fetch('/graph')
      .then((r) => (r.ok ? (r.json() as Promise<GraphDto>) : undefined))
      .then((g) => {
        if (g && !this.sawFrame) onGraph(g);
      })
      .catch(() => {
        /* SSE will deliver the first frame */
      });
    this.open(onGraph, onState);
  }

  private open(onGraph: (g: GraphDto) => void, onState: (s: ConnState) => void): void {
    if (this.stopped) return;
    onState(this.sawFrame ? 'reconnecting' : 'connecting');
    const es = new EventSource('/events');
    this.es = es;
    es.onopen = () => {
      this.backoff = 1000;
      onState('live');
    };
    es.onmessage = (e) => {
      this.sawFrame = true;
      try {
        onGraph(JSON.parse(e.data) as GraphDto);
      } catch (err) {
        console.warn('deliberate: bad /events frame', err);
      }
    };
    es.onerror = () => {
      onState('reconnecting');
      if (es.readyState === EventSource.CLOSED) {
        es.close();
        clearTimeout(this.retryTimer);
        this.retryTimer = setTimeout(() => this.open(onGraph, onState), this.backoff);
        this.backoff = Math.min(this.backoff * 2, 15000);
      }
    };
  }

  stop(): void {
    this.stopped = true;
    clearTimeout(this.retryTimer);
    this.es?.close();
    this.es = undefined;
  }

  ask(text: string): Promise<string> {
    return postQuestion(text);
  }

  override(id: string, mode: Parameters<typeof postOverride>[1]): Promise<void> {
    return postOverride(id, mode);
  }
}
