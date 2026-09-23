//! `computenet-iroh-dns` — a self-hosted pkarr relay + DNS server, so
//! `LookupMode::Rendezvous` never has to reach n0's public rendezvous
//! infrastructure (feature computenet-vnscs, DSC2).
//!
//! It serves the pkarr PUT/GET HTTP route and the DNS listener **without
//! TLS**, both bound to loopback: `iroh_dns_server::Config` allows `https =
//! None` (the crate's own `spawn_for_tests_with_options` does the same), so
//! this binary never needs a certificate. That is deliberate and is what
//! limits it: this is for local development and CI, never for a deployment
//! that must be reachable off the host it runs on.
//!
//! ```text
//! computenet-iroh-dns [--port <port>] [--dns-port <port>] [--dns-origin <domain>]
//! ```
//!
//! * `--port` — the loopback TCP port for the HTTP/pkarr listener. `0` (the
//!   default) is ephemeral; read the port back off the announcement line.
//! * `--dns-port` — the loopback UDP+TCP port for the DNS listener. `0` (the
//!   default) is ephemeral.
//! * `--dns-origin` — the zone the server answers `<endpointid>.<origin>`
//!   queries under. Defaults to `irohdns.example.`, the spelling the crate's
//!   own `integration_smoke` test resolves through its DNS half.
//!
//! Configuration deliberately turns off everything this binary does not need:
//! no HTTPS listener, no metrics server, no mainline DHT fallback (a
//! rendezvous here resolves known keys only — aas-D5), and PUT rate limiting
//! disabled, because every loopback client shares one peer IP and the
//! server's default (`RateLimitConfig::Simple`, 4/s burst 2 per peer IP)
//! would 429 a CI lane's dozen-plus publishers. The signed-packet store lives
//! under a pid-named directory in the OS temp dir; nothing removes it when
//! the process is killed, which is fine — it is a few KB and the process is
//! never expected to restart in place.
//!
//! Like the relay binary, it writes **one** JSON line to stdout and nothing
//! else — everything else goes to stderr — so a script can scrape both
//! addresses and the origin and hand them to
//! `computenet-iroh-sidecar --pkarr-relay-url --dns-origin`:
//!
//! ```text
//! {"port":49312,"pkarrRelayUrl":"http://127.0.0.1:49312/pkarr","dnsOrigin":"irohdns.example.","dnsAddr":"127.0.0.1:49313"}
//! ```
//!
//! After that line the process serves until it is killed.

use std::{
    io::Write,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    path::PathBuf,
    process::ExitCode,
};

use iroh_dns_server::{
    config::{Config, MetricsConfig, RateLimitConfig},
    Server,
};

#[tokio::main]
async fn main() -> ExitCode {
    match run().await {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("computenet-iroh-dns: {message}");
            ExitCode::FAILURE
        }
    }
}

async fn run() -> Result<(), String> {
    let args = Args::parse(std::env::args().skip(1))?;

    let data_dir = std::env::temp_dir().join(format!("computenet-iroh-dns-{}", std::process::id()));
    std::fs::create_dir_all(&data_dir)
        .map_err(|e| format!("creating the data dir {} failed: {e}", data_dir.display()))?;

    let config = loopback_config(args.port, args.dns_port, args.dns_origin.clone(), data_dir);

    let server = Server::bind(config)
        .await
        .map_err(|e| format!("binding the dns server failed: {e:#}"))?;

    // With no TLS configured the HTTP listener *is* the plain-HTTP one, and
    // `http_addr`/`dns_addr` report the addresses actually bound — which is
    // how `--port 0`/`--dns-port 0` can be answered with real ports.
    let http_addr = server
        .http_addr()
        .ok_or("the server reported no HTTP address")?;
    let dns_addr = server.dns_addr();

    // The one and only announcement line. Anything else on stdout would break
    // the contract a caller scrapes it with.
    let mut stdout = std::io::stdout();
    writeln!(
        stdout,
        "{}",
        announcement_line(http_addr, dns_addr, &args.dns_origin)
    )
    .and_then(|()| stdout.flush())
    .map_err(|e| format!("writing the announcement line failed: {e}"))?;

    // Serve until killed. `join` only returns if the server's supervisor
    // stops, which for this configuration means something went wrong.
    server
        .join()
        .await
        .map_err(|e| format!("the server stopped: {e:#}"))
}

/// The configuration shared by the binary's `run` and its own unit test:
/// HTTP + DNS on loopback with no TLS, no metrics, no mainline DHT, and PUT
/// rate limiting disabled.
fn loopback_config(http_port: u16, dns_port: u16, origin: String, data_dir: PathBuf) -> Config {
    let mut config = Config::default();

    let http = config
        .http
        .as_mut()
        .expect("Config::default() configures an HTTP listener");
    http.port = http_port;
    http.bind_addr = Some(IpAddr::V4(Ipv4Addr::LOCALHOST));

    // No TLS, ever — the relay binary's same limitation.
    config.https = None;

    config.dns.port = dns_port;
    config.dns.bind_addr = Some(IpAddr::V4(Ipv4Addr::LOCALHOST));
    // The root zone "." must stay in `origins` alongside the caller's origin:
    // the static authority the crate builds is rooted at `Name::root()` and
    // requires an SOA record there (`create_static_authority` in the crate's
    // own src/dns.rs), which only exists when "." is one of the configured
    // origins — `Config::default()` keeps it for the same reason.
    config.dns.origins = vec![origin, ".".to_string()];

    // No metrics server: this binary is for tests and CI, not for operation.
    config.metrics = Some(MetricsConfig::disabled());

    // `mainline` stays `None`: no BitTorrent DHT fallback. A rendezvous here
    // resolves known keys only (aas-D5).
    config.mainline = None;

    // The default (`RateLimitConfig::Simple`, 4/s burst 2) rate-limits by
    // peer IP; every loopback client shares 127.0.0.1, so it would 429 a CI
    // lane's dozen-plus publishers. Disable it entirely.
    config.pkarr_put_rate_limit = RateLimitConfig::Disabled;

    // Left behind on kill: a few KB, and the process is never expected to
    // restart in place.
    config.data_dir = Some(data_dir);

    config
}

/// The single stdout line. `origin` is echoed verbatim; callers are expected
/// to reject a `"`-bearing origin before this point (see `Args::parse`), so
/// this never has to escape it.
fn announcement_line(http_addr: SocketAddr, dns_addr: SocketAddr, origin: &str) -> String {
    format!(
        "{{\"port\":{},\"pkarrRelayUrl\":\"http://{}/pkarr\",\"dnsOrigin\":\"{}\",\"dnsAddr\":\"{}\"}}",
        http_addr.port(),
        http_addr,
        origin,
        dns_addr
    )
}

#[derive(Debug)]
struct Args {
    port: u16,
    dns_port: u16,
    dns_origin: String,
}

impl Args {
    fn parse(args: impl Iterator<Item = String>) -> Result<Self, String> {
        let mut parsed = Args {
            port: 0,
            dns_port: 0,
            dns_origin: "irohdns.example.".to_string(),
        };
        let mut args = args;
        while let Some(arg) = args.next() {
            match arg.as_str() {
                "--port" => {
                    let raw = args.next().ok_or("--port needs a value")?;
                    parsed.port = raw
                        .parse()
                        .map_err(|e| format!("--port {raw} is not a port number: {e}"))?;
                }
                "--dns-port" => {
                    let raw = args.next().ok_or("--dns-port needs a value")?;
                    parsed.dns_port = raw
                        .parse()
                        .map_err(|e| format!("--dns-port {raw} is not a port number: {e}"))?;
                }
                "--dns-origin" => {
                    let raw = args.next().ok_or("--dns-origin needs a value")?;
                    if raw.contains('"') {
                        return Err(format!(
                            "--dns-origin {raw} must not contain a double quote"
                        ));
                    }
                    parsed.dns_origin = raw;
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
    fn the_default_ports_are_ephemeral_and_the_origin_is_irohdns_example() {
        let args = parse(&[]).expect("no arguments is valid");
        assert_eq!(args.port, 0);
        assert_eq!(args.dns_port, 0);
        assert_eq!(args.dns_origin, "irohdns.example.");
    }

    #[test]
    fn ports_and_origin_are_accepted() {
        let args = parse(&[
            "--port",
            "49312",
            "--dns-port",
            "49313",
            "--dns-origin",
            "example.test.",
        ])
        .expect("accepted");
        assert_eq!(args.port, 49312);
        assert_eq!(args.dns_port, 49313);
        assert_eq!(args.dns_origin, "example.test.");
    }

    #[test]
    fn a_non_numeric_port_is_refused_at_parse_time() {
        let message = parse(&["--port", "http://relay"]).expect_err("refused");
        assert!(
            message.contains("--port"),
            "the diagnostic names the flag: {message}"
        );

        let message = parse(&["--dns-port", "http://relay"]).expect_err("refused");
        assert!(
            message.contains("--dns-port"),
            "the diagnostic names the flag: {message}"
        );
    }

    #[test]
    fn a_port_without_a_value_is_refused() {
        parse(&["--port"]).expect_err("refused");
        parse(&["--dns-port"]).expect_err("refused");
        parse(&["--dns-origin"]).expect_err("refused");
    }

    #[test]
    fn an_unknown_argument_is_refused() {
        parse(&["--relay-url", "http://relay"]).expect_err("refused");
    }

    #[test]
    fn an_origin_containing_a_quote_is_refused() {
        let message = parse(&["--dns-origin", "bad\".example."]).expect_err("refused");
        assert!(
            message.contains("--dns-origin"),
            "the diagnostic names the flag: {message}"
        );
    }

    #[test]
    fn the_announcement_line_carries_both_bound_addresses_the_pkarr_url_and_the_origin() {
        let http_addr: SocketAddr = "127.0.0.1:49312".parse().expect("literal addr");
        let dns_addr: SocketAddr = "127.0.0.1:49313".parse().expect("literal addr");
        assert_eq!(
            announcement_line(http_addr, dns_addr, "irohdns.example."),
            r#"{"port":49312,"pkarrRelayUrl":"http://127.0.0.1:49312/pkarr","dnsOrigin":"irohdns.example.","dnsAddr":"127.0.0.1:49313"}"#
        );
    }

    #[tokio::test]
    async fn the_server_binds_on_loopback_and_answers_its_pkarr_route() {
        let data_dir = std::env::temp_dir().join(format!(
            "computenet-iroh-dns-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("now is after the epoch")
                .as_nanos()
        ));
        std::fs::create_dir_all(&data_dir).expect("creating the test data dir");

        let config = loopback_config(0, 0, "irohdns.example.".to_string(), data_dir.clone());
        let server = Server::bind(config).await.expect("binding on loopback");

        let http_addr = server.http_addr().expect("an HTTP address is reported");
        let dns_addr = server.dns_addr();
        assert_eq!(http_addr.ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
        assert_ne!(http_addr.port(), 0);
        assert_eq!(dns_addr.ip(), IpAddr::V4(Ipv4Addr::LOCALHOST));
        assert_ne!(dns_addr.port(), 0);

        // A successful TCP connect to the HTTP address is enough evidence the
        // listener is actually up; no HTTP client dependency is added just to
        // prove this.
        tokio::net::TcpStream::connect(http_addr)
            .await
            .expect("connecting to the pkarr HTTP listener");

        server.shutdown().await.expect("shutting the server down");
        let _ = std::fs::remove_dir_all(&data_dir);
    }
}
