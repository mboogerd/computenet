//! `computenet-iroh-relay` — a self-hosted iroh relay, so a test run never has
//! to reach n0's public relay infrastructure (feature computenet-o0m3).
//!
//! It serves the relay's HTTP services **without TLS** on loopback. That is
//! deliberate and is what limits it: iroh's relay client speaks plaintext only
//! for an `http://` url (iroh-relay 1.0.3 `src/client/tls.rs:97-101`), so this
//! binary is for local development and CI, never for a deployment where the
//! relay and its clients are not on the same host.
//!
//! ```text
//! computenet-iroh-relay [--port <port>]
//! ```
//!
//! * `--port` — the loopback TCP port to serve on. `0` (the default) is
//!   ephemeral; read the port back off the announcement line.
//!
//! Like the sidecar binary it writes **one** JSON line to stdout and nothing
//! else — everything else goes to stderr — so a script can scrape the relay url
//! and hand it to `computenet-iroh-sidecar --relay-url`:
//!
//! ```text
//! {"port":49312,"relayUrl":"http://127.0.0.1:49312"}
//! ```
//!
//! After that line the process serves until it is killed.

use std::{io::Write, net::SocketAddr, process::ExitCode};

use iroh_relay::server::{RelayConfig, Server, ServerConfig};

#[tokio::main]
async fn main() -> ExitCode {
    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("computenet-iroh-relay: {message}");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> Result<(), String> {
    let args = Args::parse(std::env::args().skip(1))?;

    // `ServerConfig` is `#[non_exhaustive]`, so it is built by mutation rather
    // than by a struct literal. No QUIC address-discovery server and no metrics
    // endpoint: the relay's rendezvous is the whole point of this binary.
    let mut config = ServerConfig::default();
    config.relay = Some(RelayConfig::new(SocketAddr::from((
        [127, 0, 0, 1],
        args.port,
    ))));

    let mut server = Server::spawn(config)
        .await
        .map_err(|e| format!("spawning the relay failed: {e}"))?;

    // With no TLS configured the relay's own listener *is* the plain-HTTP one,
    // and `http_addr` reports the address it actually bound — which is how
    // `--port 0` can be answered with a real port (iroh-relay 1.0.3
    // `src/server.rs:863` over `src/server/http_server.rs:459-465`).
    let addr = server
        .http_addr()
        .ok_or("the relay reported no HTTP address")?;

    // The one and only announcement line. Anything else on stdout would break
    // the contract a caller scrapes it with.
    let mut stdout = std::io::stdout();
    writeln!(stdout, "{}", announcement_line(addr))
        .and_then(|()| stdout.flush())
        .map_err(|e| format!("writing the announcement line failed: {e}"))?;

    // Serve until killed. `join` only returns if the relay's supervisor stops,
    // which for this configuration means something went wrong.
    match server.join().await {
        Ok(Ok(())) => Ok(()),
        Ok(Err(e)) => Err(format!("the relay stopped: {e}")),
        Err(e) => Err(format!("the relay task failed: {e}")),
    }
}

/// The single stdout line, mirroring the sidecar's handshake-line idiom: one
/// JSON object, no trailing content.
fn announcement_line(addr: SocketAddr) -> String {
    format!(
        "{{\"port\":{},\"relayUrl\":\"{}\"}}",
        addr.port(),
        relay_url(addr)
    )
}

/// The url a client dials this relay on. Plain `http`, because this relay
/// serves without TLS.
fn relay_url(addr: SocketAddr) -> String {
    format!("http://{addr}")
}

#[derive(Debug)]
struct Args {
    port: u16,
}

impl Args {
    fn parse(args: impl Iterator<Item = String>) -> Result<Self, String> {
        let mut parsed = Args { port: 0 };
        let mut args = args;
        while let Some(arg) = args.next() {
            match arg.as_str() {
                "--port" => {
                    let raw = args.next().ok_or("--port needs a value")?;
                    parsed.port = raw
                        .parse()
                        .map_err(|e| format!("--port {raw} is not a port number: {e}"))?;
                }
                other => return Err(format!("unknown argument {other}")),
            }
        }
        Ok(parsed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(args: &[&str]) -> Result<Args, String> {
        Args::parse(args.iter().map(|s| s.to_string()))
    }

    #[test]
    fn the_default_port_is_ephemeral() {
        assert_eq!(parse(&[]).expect("no arguments is valid").port, 0);
    }

    #[test]
    fn a_port_is_accepted() {
        assert_eq!(parse(&["--port", "49312"]).expect("accepted").port, 49312);
    }

    #[test]
    fn a_non_numeric_port_is_refused_at_parse_time() {
        let message = parse(&["--port", "http://relay"]).expect_err("refused");
        assert!(
            message.contains("--port"),
            "the diagnostic names the flag: {message}"
        );
    }

    #[test]
    fn a_port_without_a_value_is_refused() {
        parse(&["--port"]).expect_err("refused");
    }

    #[test]
    fn an_unknown_argument_is_refused() {
        parse(&["--relay-url", "http://relay"]).expect_err("refused");
    }

    #[test]
    fn the_announcement_line_carries_the_bound_port_and_a_plain_http_url() {
        let addr: SocketAddr = "127.0.0.1:49312".parse().expect("literal addr");
        assert_eq!(
            announcement_line(addr),
            r#"{"port":49312,"relayUrl":"http://127.0.0.1:49312"}"#
        );
    }
}
