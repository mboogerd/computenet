//! The sidecar binary — a thin wrapper over the library (feature decision
//! egl.1-D2).
//!
//! It binds an iroh endpoint, binds an ephemeral `127.0.0.1` TCP port, writes
//! **one** JSON handshake line to stdout, and then hands each host connection to
//! [`computenet_iroh_sidecar::serve`]. Everything the host can ask for is
//! described in `PROTOCOL.md`; nothing about the protocol is decided here.
//!
//! ```text
//! computenet-iroh-sidecar [--offline] [--relay-url <url>] [--mdns]
//!                         [--pkarr-relay-url <url> --dns-origin <domain>]
//!                         [--dns-nameserver <ip:port>]
//!                         [--secret-key <64 hex chars>]
//!                         [--bind-addr <ip:port>]... [--socket-port <port>]
//! ```
//!
//! * `--offline` — resolve peers only from `ADD_PEER`, never a relay or DNS.
//!   Bind loopback only. This is what makes a test run network-free.
//! * `--relay-url` — use exactly this relay in place of number 0's public
//!   relays. Alone it also disables address lookup entirely; with the
//!   rendezvous flags below it is the relay that rendezvous mode publishes.
//!   Mutually exclusive with `--offline`.
//! * `--pkarr-relay-url` / `--dns-origin` — a **self-hosted** rendezvous: the
//!   endpoint publishes its address record to this pkarr relay and resolves
//!   peers from it and by DNS under this origin, reaching number 0 for
//!   nothing. The two require each other, and neither can be combined with
//!   `--offline`.
//! * `--dns-nameserver` — point the endpoint's DNS resolver at exactly this
//!   UDP nameserver rather than the host's. Requires the two flags above;
//!   omitted, the operator is expected to have delegated the origin zone so
//!   the system resolver reaches it.
//! * `--mdns` — also enumerate peers on the local network segment via
//!   `iroh-mdns-address-lookup`. Orthogonal to `--offline`/`--relay-url`: it
//!   composes with every other flag. If the mDNS service cannot bind, the
//!   sidecar reports that once on stderr and continues without it.
//! * `--secret-key` — the ed25519 secret key as 32 bytes of hex. Omitted, a
//!   fresh key is generated, so the endpoint id changes every run.
//! * `--bind-addr` — a UDP socket for the iroh endpoint; repeatable.
//! * `--socket-port` — the loopback TCP port. `0` (the default) is ephemeral.

use std::{io::Write, net::SocketAddr, process::ExitCode, str::FromStr};

use computenet_iroh_sidecar::{
    handshake_line, protocol::decode_hex, serve, LookupMode, SecretKey, ServeOutcome,
    SidecarConfig, SidecarEndpoint,
};
use iroh::RelayUrl;
use tokio::net::TcpListener;
use url::Url;

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
        // `offline_loopback` also pins the bind addresses to loopback, which
        // is why this branch is not simply `lookup: args.lookup_mode()`.
        SidecarConfig::offline_loopback()
    } else {
        SidecarConfig {
            lookup: args.lookup_mode(),
            ..Default::default()
        }
    };
    config.mdns = args.mdns;
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

#[derive(Debug)]
struct Args {
    offline: bool,
    relay_url: Option<RelayUrl>,
    pkarr_relay_url: Option<Url>,
    dns_origin: Option<String>,
    dns_nameserver: Option<SocketAddr>,
    mdns: bool,
    secret_key: Option<SecretKey>,
    bind_addrs: Vec<SocketAddr>,
    socket_port: u16,
}

impl Args {
    /// The lookup mode these flags select (F2-D7: no default changed — no
    /// flag is still `N0`). `Args::parse` has already refused every
    /// combination this does not cover, so the rendezvous arm can take the
    /// pair as present.
    fn lookup_mode(&self) -> LookupMode {
        if self.offline {
            return LookupMode::Offline;
        }
        match (&self.pkarr_relay_url, &self.dns_origin) {
            (Some(pkarr_relay), Some(dns_origin)) => LookupMode::Rendezvous {
                pkarr_relay: pkarr_relay.clone(),
                dns_origin: dns_origin.clone(),
                dns_nameserver: self.dns_nameserver,
                relay: self.relay_url.clone(),
            },
            _ => match &self.relay_url {
                Some(url) => LookupMode::Relay(url.clone()),
                None => LookupMode::N0,
            },
        }
    }

    fn parse(args: impl Iterator<Item = String>) -> Result<Self, String> {
        let mut parsed = Args {
            offline: false,
            relay_url: None,
            pkarr_relay_url: None,
            dns_origin: None,
            dns_nameserver: None,
            mdns: false,
            secret_key: None,
            bind_addrs: Vec::new(),
            socket_port: 0,
        };
        let mut args = args.peekable();
        while let Some(arg) = args.next() {
            match arg.as_str() {
                "--offline" => parsed.offline = true,
                "--mdns" => parsed.mdns = true,
                "--relay-url" => {
                    let raw = args.next().ok_or("--relay-url needs a value")?;
                    // Parsed here rather than at bind time, so a typo fails
                    // before any socket is bound or any handshake line written.
                    parsed.relay_url = Some(
                        RelayUrl::from_str(&raw)
                            .map_err(|e| format!("--relay-url {raw} is not a URL: {e}"))?,
                    );
                }
                "--pkarr-relay-url" => {
                    let raw = args.next().ok_or("--pkarr-relay-url needs a value")?;
                    // Parsed here for the same reason --relay-url is: a typo
                    // fails before any socket is bound.
                    parsed.pkarr_relay_url = Some(
                        Url::parse(&raw)
                            .map_err(|e| format!("--pkarr-relay-url {raw} is not a URL: {e}"))?,
                    );
                }
                "--dns-origin" => {
                    let raw = args.next().ok_or("--dns-origin needs a value")?;
                    if raw.is_empty() {
                        return Err("--dns-origin needs a non-empty domain".to_string());
                    }
                    parsed.dns_origin = Some(raw);
                }
                "--dns-nameserver" => {
                    let raw = args.next().ok_or("--dns-nameserver needs a value")?;
                    parsed.dns_nameserver = Some(raw.parse().map_err(|e| {
                        format!("--dns-nameserver {raw} is not a socket address: {e}")
                    })?);
                }
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
        if parsed.offline && parsed.relay_url.is_some() {
            return Err(
                "--offline and --relay-url cannot be combined: --offline uses no relay at all"
                    .to_string(),
            );
        }
        // Every rendezvous rule is checked here, before `run` binds anything.
        // The `--offline` conflict comes first, so a combination that breaks
        // two rules at once is reported as the one the operator can act on.
        let rendezvous_flags: Vec<&str> = [
            parsed
                .pkarr_relay_url
                .is_some()
                .then_some("--pkarr-relay-url"),
            parsed.dns_origin.is_some().then_some("--dns-origin"),
            parsed
                .dns_nameserver
                .is_some()
                .then_some("--dns-nameserver"),
        ]
        .into_iter()
        .flatten()
        .collect();
        if parsed.offline && !rendezvous_flags.is_empty() {
            return Err(format!(
                "--offline and {} cannot be combined: --offline resolves peers only from ADD_PEER, \
                 publishing and resolving nothing",
                rendezvous_flags.join(" and "),
            ));
        }
        if parsed.dns_nameserver.is_some()
            && (parsed.pkarr_relay_url.is_none() || parsed.dns_origin.is_none())
        {
            return Err(
                "--dns-nameserver requires both --pkarr-relay-url and --dns-origin: it only \
                 changes the resolver rendezvous mode uses"
                    .to_string(),
            );
        }
        if parsed.pkarr_relay_url.is_some() != parsed.dns_origin.is_some() {
            return Err(
                "--pkarr-relay-url and --dns-origin require each other: rendezvous mode needs \
                 both the pkarr relay to publish to and the DNS origin to resolve under"
                    .to_string(),
            );
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
    fn relay_url_is_accepted_and_parsed() {
        let args = parse(&["--relay-url", "https://relay.example.org"]).expect("accepted");
        assert_eq!(
            args.relay_url.map(|u| u.to_string()),
            Some("https://relay.example.org/".to_string())
        );
        assert!(!args.offline);
    }

    #[test]
    fn without_the_flag_the_relay_url_is_absent() {
        let args = parse(&[]).expect("accepted");
        assert_eq!(args.relay_url, None);
        assert!(!args.offline);
        assert!(!args.mdns);
    }

    #[test]
    fn mdns_parses_alone_and_composes_with_other_flags() {
        let args = parse(&["--mdns"]).expect("accepted");
        assert!(args.mdns);

        let args = parse(&["--offline", "--mdns"]).expect("accepted");
        assert!(args.mdns);
        assert!(args.offline);

        let args =
            parse(&["--mdns", "--relay-url", "https://relay.example.org"]).expect("accepted");
        assert!(args.mdns);
        assert!(args.relay_url.is_some());
    }

    #[test]
    fn offline_combined_with_relay_url_is_refused_naming_both_flags() {
        for argv in [
            ["--offline", "--relay-url", "https://relay.example.org"].as_slice(),
            ["--relay-url", "https://relay.example.org", "--offline"].as_slice(),
        ] {
            let message = parse(argv).expect_err("refused");
            assert!(
                message.contains("--offline") && message.contains("--relay-url"),
                "diagnostic must name both flags, was: {message}"
            );
        }
    }

    #[test]
    fn a_malformed_relay_url_is_refused_at_parse_time() {
        let message = parse(&["--relay-url", "not a url"]).expect_err("refused");
        assert!(
            message.contains("--relay-url"),
            "diagnostic must name the flag, was: {message}"
        );
    }

    #[test]
    fn relay_url_without_a_value_is_refused() {
        let message = parse(&["--relay-url"]).expect_err("refused");
        assert!(
            message.contains("--relay-url"),
            "diagnostic must name the flag, was: {message}"
        );
    }

    #[test]
    fn rendezvous_flags_are_accepted_together_and_compose_with_relay_url_and_mdns() {
        let args = parse(&[
            "--pkarr-relay-url",
            "http://127.0.0.1:8080/pkarr",
            "--dns-origin",
            "irohdns.example.",
        ])
        .expect("the pair alone is accepted");
        assert_eq!(
            args.lookup_mode(),
            LookupMode::Rendezvous {
                pkarr_relay: Url::parse("http://127.0.0.1:8080/pkarr").expect("literal url"),
                dns_origin: "irohdns.example.".to_string(),
                dns_nameserver: None,
                relay: None,
            }
        );

        let args = parse(&[
            "--pkarr-relay-url",
            "http://127.0.0.1:8080/pkarr",
            "--dns-origin",
            "irohdns.example.",
            "--dns-nameserver",
            "127.0.0.1:5300",
            "--relay-url",
            "https://relay.example.org",
            "--mdns",
        ])
        .expect("all four compose");
        assert!(args.mdns);
        assert_eq!(
            args.lookup_mode(),
            LookupMode::Rendezvous {
                pkarr_relay: Url::parse("http://127.0.0.1:8080/pkarr").expect("literal url"),
                dns_origin: "irohdns.example.".to_string(),
                dns_nameserver: Some("127.0.0.1:5300".parse().expect("literal addr")),
                relay: Some(
                    RelayUrl::from_str("https://relay.example.org").expect("literal relay url")
                ),
            },
            "--relay-url composes into the rendezvous rather than selecting Relay"
        );
    }

    #[test]
    fn one_rendezvous_flag_without_the_other_is_refused_naming_both() {
        for argv in [
            ["--pkarr-relay-url", "http://127.0.0.1:8080/pkarr"].as_slice(),
            ["--dns-origin", "irohdns.example."].as_slice(),
        ] {
            let message = parse(argv).expect_err("refused");
            assert!(
                message.contains("--pkarr-relay-url") && message.contains("--dns-origin"),
                "diagnostic must name both flags, was: {message}"
            );
        }
    }

    #[test]
    fn dns_nameserver_without_the_pair_is_refused_naming_all_three() {
        for argv in [
            ["--dns-nameserver", "127.0.0.1:5300"].as_slice(),
            [
                "--dns-nameserver",
                "127.0.0.1:5300",
                "--pkarr-relay-url",
                "http://127.0.0.1:8080/pkarr",
            ]
            .as_slice(),
            [
                "--dns-nameserver",
                "127.0.0.1:5300",
                "--dns-origin",
                "irohdns.example.",
            ]
            .as_slice(),
        ] {
            let message = parse(argv).expect_err("refused");
            assert!(
                message.contains("--dns-nameserver")
                    && message.contains("--pkarr-relay-url")
                    && message.contains("--dns-origin"),
                "diagnostic must name all three flags, was: {message}"
            );
        }
    }

    #[test]
    fn offline_combined_with_a_rendezvous_flag_is_refused_naming_both() {
        for (flag, value) in [
            ("--pkarr-relay-url", "http://127.0.0.1:8080/pkarr"),
            ("--dns-origin", "irohdns.example."),
            ("--dns-nameserver", "127.0.0.1:5300"),
        ] {
            for argv in [
                vec!["--offline", flag, value],
                vec![flag, value, "--offline"],
            ] {
                let message = parse(&argv).expect_err("refused");
                assert!(
                    message.contains("--offline") && message.contains(flag),
                    "diagnostic must name --offline and {flag}, was: {message}"
                );
            }
        }
    }

    #[test]
    fn a_malformed_pkarr_relay_url_or_nameserver_is_refused_at_parse_time() {
        let message = parse(&[
            "--pkarr-relay-url",
            "not a url",
            "--dns-origin",
            "irohdns.example.",
        ])
        .expect_err("refused");
        assert!(
            message.contains("--pkarr-relay-url"),
            "diagnostic must name the flag, was: {message}"
        );

        let message = parse(&[
            "--pkarr-relay-url",
            "http://127.0.0.1:8080/pkarr",
            "--dns-origin",
            "irohdns.example.",
            "--dns-nameserver",
            "not an address",
        ])
        .expect_err("refused");
        assert!(
            message.contains("--dns-nameserver"),
            "diagnostic must name the flag, was: {message}"
        );

        let message = parse(&[
            "--pkarr-relay-url",
            "http://127.0.0.1:8080/pkarr",
            "--dns-origin",
            "",
        ])
        .expect_err("refused");
        assert!(
            message.contains("--dns-origin"),
            "diagnostic must name the flag, was: {message}"
        );
    }

    #[test]
    fn without_the_flags_the_lookup_choice_is_unchanged() {
        // F2-D7: the rendezvous flags added no default and moved no existing
        // mode. This is the [DSC2-RDV-06] half main.rs owns.
        assert_eq!(parse(&[]).expect("accepted").lookup_mode(), LookupMode::N0);
        assert_eq!(
            parse(&["--relay-url", "https://relay.example.org"])
                .expect("accepted")
                .lookup_mode(),
            LookupMode::Relay(
                RelayUrl::from_str("https://relay.example.org").expect("literal relay url")
            )
        );
        assert_eq!(
            parse(&["--offline"]).expect("accepted").lookup_mode(),
            LookupMode::Offline
        );
    }
}
