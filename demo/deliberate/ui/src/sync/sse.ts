import { postOverride, postPause, postQuestion } from '../api/commands';
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
  private generation = 0;

  start(onGraph: (g: GraphDto) => void, onState: (s: ConnState) => void): void {
    this.stop();
    const generation = ++this.generation;
    this.stopped = false;
    this.sawFrame = false;
    this.backoff = 1000;
    void fetch('/graph')
      .then((r) => (r.ok ? (r.json() as Promise<GraphDto>) : undefined))
      .then((g) => {
        if (g && this.isCurrent(generation) && !this.sawFrame) onGraph(g);
      })
      .catch(() => {
        /* SSE will deliver the first frame */
      });
    this.open(generation, onGraph, onState);
  }

  private open(generation: number, onGraph: (g: GraphDto) => void, onState: (s: ConnState) => void): void {
    if (!this.isCurrent(generation)) return;
    onState(this.sawFrame ? 'reconnecting' : 'connecting');
    const es = new EventSource('/events');
    this.es = es;
    es.onopen = () => {
      if (!this.isCurrent(generation) || this.es !== es) return;
      this.backoff = 1000;
      onState('live');
    };
    es.onmessage = (e) => {
      if (!this.isCurrent(generation) || this.es !== es) return;
      try {
        const graph = JSON.parse(e.data) as GraphDto;
        this.sawFrame = true;
        onGraph(graph);
      } catch (err) {
        console.warn('deliberate: bad /events frame', err);
      }
    };
    es.onerror = () => {
      if (!this.isCurrent(generation) || this.es !== es) return;
      onState('reconnecting');
      if (es.readyState === EventSource.CLOSED) {
        es.close();
        clearTimeout(this.retryTimer);
        this.retryTimer = setTimeout(() => this.open(generation, onGraph, onState), this.backoff);
        this.backoff = Math.min(this.backoff * 2, 15000);
      }
    };
  }

  private isCurrent(generation: number): boolean {
    return !this.stopped && generation === this.generation;
  }

  stop(): void {
    this.stopped = true;
    this.generation++;
    clearTimeout(this.retryTimer);
    this.retryTimer = undefined;
    this.es?.close();
    this.es = undefined;
  }

  ask(text: string): Promise<string> {
    return postQuestion(text);
  }

  override(id: string, mode: Parameters<typeof postOverride>[1]): Promise<void> {
    return postOverride(id, mode);
  }

  pause(root: string, paused: boolean): Promise<void> {
    return postPause(root, paused);
  }
}
