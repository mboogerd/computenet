# A TCP reset that races `accept()` closes the server's listening socket

## What happens

`WebSocketServer.doAccept` configures the freshly accepted socket before it has
anything to attribute a failure to:

```java
SocketChannel channel = server.accept();
if (channel == null) return;
channel.configureBlocking(false);
Socket socket = channel.socket();
socket.setTcpNoDelay(isTcpNoDelay());
socket.setKeepAlive(true);
```

`doAccept`'s own `try` starts three statements later, at `w.setChannel(...)`,
and the method declares `throws IOException`. So an exception from those setters
unwinds into `run()`'s last resort:

```java
} catch (IOException ex) {
  handleIOException(key, null, ex);
}
```

`key` there is the **server's** acceptable key. `handleIOException` cancels it
and, with no `WebSocket` to blame, closes `key.channel()` — the listening
channel.

The result is a server that is alive and deaf. The object stays up, the selector
thread stays up, existing connections keep working, and the bound port is gone.
Clients get `ECONNREFUSED` forever. Nothing is reported: `handleIOException` only
`log.trace()`s, and `onError` is `handleFatal`'s path, not this one — so on a
deployment with no SLF4J provider the failure is completely silent.

## When it happens

When a peer's RST is already delivered by the time the connection is accepted.
Closing a TCP socket that still holds unread data sends RST rather than FIN, so
ordinary clients produce this — including this library's own
`WebSocketClient.reset()`.

## Platform scope, measured

The trigger is `setsockopt` on a socket whose connection is already torn down.
10 trials each, same JDK 21 class file:

| step | macOS 26.6 (aarch64) | Linux 6.12 (Temurin 21.0.11, container, aarch64) |
|---|---|---|
| `close()` with `SO_LINGER 0` delivers RST | yes | yes |
| `accept()` throws | 0/10 | 0/10 |
| `setTcpNoDelay`/`setKeepAlive` on the reset victim throws | **10/10**, `SocketException: Invalid argument` | 0/10, both succeed |

A 3000-cycle reset storm through a plain `Selector` acceptor raises
`SocketException: Invalid argument` from `setTcpNoDelay` on 2997/3000 accepts on
macOS and on 0/3000 on Linux. At the syscall level, `setsockopt(TCP_NODELAY)` on
a reset victim returns `EINVAL` 10/10 on macOS.

So this is a BSD-family defect. On Linux the reset surfaces at the first
per-connection read, where `handleIOException` receives the doomed connection's
own `WebSocket`, cancels the right key and closes the right channel — the
existing behaviour there is correct.

## Reproduction

Against an unmodified 1.6.0 jar, with **no** third-party code: a bare
`WebSocketServer` bound to `localhost:0`, then repeatedly connect with
`SO_LINGER 0` and close immediately. The listening socket is unbound
(`ECONNREFUSED`, still refused 500 ms later) after 1–21 resets, **25/25 trials**
on macOS 26.6 / JDK 21, and `onError` is invoked **0 times** across all 25. The
same probe on Linux: 0/15 trials.

## The fix

Configure the accepted socket inside its own `try/catch` and close that channel
on failure, so the exception is charged to the connection it came from instead of
to the server's acceptable key. `doAccept` already returns early without
`i.remove()` when `accept()` yields `null`, so the discard path is not a new
state for the selector loop.

See the attached patch.

## Notes for review

- The `catch` is `IOException` rather than `SocketException` because
  `configureBlocking` is in the same window and throws `IOException`.
- `log.trace` matches how the surrounding code reports a discarded connection.
- No behaviour change on Linux, where these setters do not throw for this
  stimulus.
