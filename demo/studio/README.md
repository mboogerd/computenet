# Studio

Run the backend with `./gradlew :demo:studio:run --args='18440 --data /tmp/studio'`.
For development, run `npm install && npm run dev` in `demo/studio/ui/`.
For a built UI, run `npm run build` there, then `./gradlew :demo:studio:installDist`.
From the repository root, launch
`demo/studio/build/install/studio/bin/studio --ui demo/studio/ui/dist`.
Default data: `~/.local/share/computenet/studio/default`; default port: 18440.

Text nodes submit changes; Git nodes watch commit refs (first observation is silent).
Exec nodes invoke argv templates or read-only Claude/Codex agents. Program nodes
generate Python 3 stdlib code; approve its SHA-256 before running. Inputs dedupe
equal changes and compose pending changes while a process runs. Every canvas node
is a kernel cell; live wires use admitted staged links and catch-up.

Fire adds an ISO-8601 `fired` timestamp to the stored text/git change, triggering
a fresh downstream run even when the text or ref is unchanged. Restart and re-link
catch-up replay that stored change unchanged. Exec/program runs with unchanged
stdout retain their output and record run history without emitting downstream.
Approving an automatic program runs it once all inputs are held; unapproved
automatic programs wait in `stale` with a request to generate and approve code.
Blank agent models use the CLI default, including the user’s Codex configuration.

The service binds loopback, validates Host on all routes, requires JSON on
POST/PATCH, and sends no CORS headers. Agents use read-only tools; generation
has no tools. Custom commands and approved Python execute locally with your OS
permissions: inspect commands and generated code before running them. This is a
local development tool, not a sandbox for untrusted programs.

Graph/state writes are atomic plain files, with per-run stdout, stderr and metadata.
Restart restores state without re-running nodes. Guarded SSE uses DemoShell's
framing helpers because its SSE registration API has no request-filter hook.
