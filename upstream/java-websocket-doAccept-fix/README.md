# Upstream fix for java-websocket 1.6.0 `doAccept`, prepared but NOT submitted

## Status

**Not submitted.** No pull request has been opened against
https://github.com/TooTallNate/Java-WebSocket, and none may be opened from this
repository's automation: submitting it is an outward-facing action that needs a
human to take it. This directory holds the patch and the intended PR text so
that a human can do so in one step.

`computenet-dqy.37` carries the note that the upstream submission is pending.
When a PR is opened, record its link on that bead.

## Contents

- `doAccept-attribute-config-failure-to-the-accepted-channel.patch` — the fix.
  Written against the 1.6.0 sources jar
  (`org.java-websocket:Java-WebSocket:1.6.0`, `WebSocketServer.java`); the hunk
  is context-anchored rather than line-anchored because upstream `master` has
  moved on. Re-check the surrounding lines before applying.
- `PR-DESCRIPTION.md` — the issue/PR body: mechanism, measurements, platform
  scope, reproduction, and the fix.

## Why this repository also vendors the fix

`WsTransport.WsListener` (`wire/src/main/kotlin/civictech/wire/WsTransport.kt`)
carries the same repair locally, because `doAccept` and `handleIOException` are
both `private` in 1.6.0 and cannot be overridden — see
`WsListener.takeOverAccepting`'s KDoc for what that costs and
`WsListenerAcceptRstTest` for the executable characterization.

**That vendored code is written to be retired.** When a java-websocket release
carrying this fix ships, `takeOverAccepting`, `acceptLoop` and `admit` delete,
`onConnect` goes back to capturing the listening channel only, and
`WsListenerAcceptRstTest` keeps asserting exactly what it asserts now.
