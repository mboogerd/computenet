//! The sidecar binary — a thin wrapper over the library (feature decision
//! egl.1-D2).
//!
//! It binds an iroh endpoint, binds an ephemeral `127.0.0.1` TCP port, writes
//! **one** JSON handshake line to stdout, and then hands each host connection to
//! [`computenet_iroh_sidecar::serve`]. Everything the host can ask for is
//! described in `PROTOCOL.md`; nothing about the protocol is decided here.
//!
//! ```text
//! computenet-iroh-sidecar [--offline] [--secret-key <64 hex chars>]
//!                         [--bind-addr <ip:port>]... [--socket-port <port>]
//! ```
//!
//! * `--offline` — resolve peers only from `ADD_PEER`, never a relay or DNS.
//!   Bind loopback only. This is what makes a test run network-free.
//! * `--secret-key` — the ed25519 secret key as 32 bytes of hex. Omitted, a
//!   fresh key is generated, so the endpoint id changes every run.
//! * `--bind-addr` — a UDP socket for the iroh endpoint; repeatable.
//! * `--socket-port` — the loopback TCP port. `0` (the default) is ephemeral.

use std::{io::Write, net::SocketAddr, process::ExitCode};

use computenet_iroh_sidecar::{
    handshake_line, protocol::decode_hex, serve, LookupMode, SecretKey, ServeOutcome,
    SidecarConfig, SidecarEndpoint,
};
use tokio::net::TcpListener;

#[tokio::main]
async fn main() -> ExitCode {
    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("computenet-iroh-sidecar: {message}");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> Result<(), String> {
    let args = Args::parse(std::env::args().skip(1))?;

    let mut config = if args.offline {
        SidecarConfig::offline_loopback()
    } else {
        SidecarConfig {
            lookup: LookupMode::N0,
            ..Default::default()
        }
    };
    if !args.bind_addrs.is_empty() {
        config.bind_addrs = args.bind_addrs;
    }
    if let Some(secret) = args.secret_key {
        config = config.with_secret_key(secret);
    }

    let endpoint = SidecarEndpoint::bind(config)
        .await
        .map_err(|e| format!("binding the iroh endpoint failed: {e}"))?;
    let listener = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], args.socket_port)))
        .await
        .map_err(|e| format!("binding the loopback socket failed: {e}"))?;
    let port = listener
        .local_addr()
        .map_err(|e| format!("reading the loopback port failed: {e}"))?
        .port();

    // The one and only handshake line. Everything after this on stdout would
    // break the contract, so diagnostics go to stderr.
    let mut stdout = std::io::stdout();
    writeln!(stdout, "{}", handshake_line(port, endpoint.id()))
        .and_then(|()| stdout.flush())
        .map_err(|e| format!("writing the handshake line failed: {e}"))?;

    loop {
        let (socket, _) = listener
            .accept()
            .await
            .map_err(|e| format!("accepting a host connection failed: {e}"))?;
        // One host connection at a time: the sidecar belongs to one host
        // process, and a second connection waits rather than sharing link ids.
        match serve(endpoint.clone(), socket).await {
            Ok(ServeOutcome::Shutdown) => break,
            Ok(ServeOutcome::Disconnected) => continue,
            Err(e) => return Err(format!("serving the host connection failed: {e}")),
        }
    }

    endpoint.close().await;
    Ok(())
}

struct Args {
    offline: bool,
    secret_key: Option<SecretKey>,
    bind_addrs: Vec<SocketAddr>,
    socket_port: u16,
}

impl Args {
    fn parse(args: impl Iterator<Item = String>) -> Result<Self, String> {
        let mut parsed = Args {
            offline: false,
            secret_key: None,
            bind_addrs: Vec::new(),
            socket_port: 0,
        };
        let mut args = args.peekable();
        while let Some(arg) = args.next() {
            match arg.as_str() {
                "--offline" => parsed.offline = true,
                "--secret-key" => {
                    let hex = args.next().ok_or("--secret-key needs a value")?;
                    let bytes = decode_hex(&hex).ok_or("--secret-key is not hex")?;
                    let bytes: [u8; 32] = bytes
                        .try_into()
                        .map_err(|_| "--secret-key must be 32 bytes (64 hex chars)")?;
                    parsed.secret_key = Some(SecretKey::from_bytes(&bytes));
                }
                "--bind-addr" => {
                    let raw = args.next().ok_or("--bind-addr needs a value")?;
                    parsed.bind_addrs.push(
                        raw.parse().map_err(|e| {
                            format!("--bind-addr {raw} is not a socket address: {e}")
                        })?,
                    );
                }
                "--socket-port" => {
                    let raw = args.next().ok_or("--socket-port needs a value")?;
                    parsed.socket_port = raw
                        .parse()
                        .map_err(|e| format!("--socket-port {raw} is not a port: {e}"))?;
                }
                other => return Err(format!("unknown argument {other}")),
            }
        }
        Ok(parsed)
    }
}
