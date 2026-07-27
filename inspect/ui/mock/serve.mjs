// Tiny fixture server for manual/offline UI development (M0-FE ticket
// Implement §1: "add a tiny `npm run mock` static server for the fixture").
// No deps — Node's built-in http module only. Serves the checked-in
// fixtures/topology.json at GET /api/inspect/topology and a heartbeat-only
// SSE stream at GET /api/inspect/events, so `npm run dev` (pointed at this
// via INSPECT_BACKEND, the vite.config.ts default) renders the skillmatch
// graph without a real :inspect server.
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const fixturePath = fileURLToPath(new URL('../fixtures/topology.json', import.meta.url));
const port = Number(process.env.PORT ?? 7071);

const server = createServer((req, res) => {
  const url = req.url ?? '';

  if (url === '/api/inspect/topology') {
    const body = readFileSync(fixturePath, 'utf8');
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(body);
    return;
  }

  if (url === '/api/inspect/events') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });
    const snapshot = JSON.parse(readFileSync(fixturePath, 'utf8'));
    let seq = snapshot.seq;
    const beat = setInterval(() => {
      seq += 1;
      res.write(`data: ${JSON.stringify({ seq, kind: 'heartbeat', payload: {} })}\n\n`);
    }, 15000);
    req.on('close', () => clearInterval(beat));
    return;
  }

  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found — this is the M0-FE fixture mock, not the real :inspect server');
});

server.listen(port, () => {
  console.log(`inspect/ui fixture mock: http://localhost:${port}`);
});
