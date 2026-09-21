//! Two sidecar endpoints meeting through a **self-hosted rendezvous** — an
//! `iroh-dns-server` running in this test process on loopback, serving both
//! the pkarr PUT/GET route and the DNS zone — with neither side ever being
//! told anything about the other.
//!
//! This file is the twin of `relay_mode.rs`. There the dialler was handed the
//! peer's relay address and the relay performed the rendezvous; here the
//! dialler is handed **nothing at all** and the dial carries a bare
//! [`EndpointId`]. The server is the same `iroh_dns_server::Server` that
//! `src/bin/dns.rs` runs, configured the same way (the ~15 lines of
//! `loopback_config` are duplicated here on purpose: the library must not
//! depend on `iroh-dns-server`, and a binary cannot be imported by a test).
//! Nothing in this file reaches n0's public relay, DNS or pkarr
//! infrastructure (feature computenet-vnscs, epic computenet-aas §4.4).
//!
//! Run it with `cargo test --test rendezvous_mode` from `iroh/sidecar`. It
//! needs no environment variables, no feature flags and no network: three
//! `#[tokio::test]`s, all loopback, all bounded.
//!
//! # Why the positive test cannot pass for a lesser reason
//!
//! `a_bare_id_dial_resolves_through_the_self_hosted_rendezvous` succeeds only
//! if the rendezvous server carried the address. Every other path is closed
//! off by construction:
//!
//! * **`add_peer` is never called in this file.** Grep it: the string appears
//!   nowhere below. The dialler's `MemoryLookup` is therefore empty for the
//!   whole run, so the id it dials cannot resolve out of local state.
//! * **`LookupMode::Rendezvous` binds on `presets::Minimal`**, so the only
//!   address lookup services are `MemoryLookup` plus the three pointed at
//!   *this* server — the publisher, the pkarr resolver and the DNS lookup.
//!   That the set is exactly those four is pinned by `endpoint.rs`'s
//!   `rendezvous_binds_memory_lookup_plus_three_custom_services`; there is no
//!   n0 DNS, no n0 pkarr, and `relay: None` leaves Minimal's relay disabled.
//! * **No mDNS or other ambient local discovery**: `SidecarConfig::mdns`
//!   defaults to `false`, and both endpoints assert `mdns().is_none()`.
//! * **Both endpoints bind `127.0.0.1:0`** with every other IP transport
//!   cleared, so neither advertises an address a peer could have guessed.
//!
//! and, empirically, by
//! `a_dial_fails_against_a_rendezvous_that_never_learned_the_peer_and_looks_like_absence`:
//! same construction, same two live loopback endpoints, same live servers —
//! only the *shared* rendezvous is denied, and then no link forms.
//!
//! # What is asserted about the clock, and what is not
//!
//! Publication is asynchronous: `PkarrPublisher` PUTs once the endpoint knows
//! its addresses. The positive tests therefore *retry the dial* under a
//! deadline ([`dial_until`]) — the sleep paces the retries, the deadline
//! bounds them, and the assertion is always the dial's outcome and never the
//! clock (feature rule 3, F2-D12). Every await that can block on a **peer** or
//! on the **rendezvous** — a dial attempt, an accept, a frame read, or the
//! negative control's settling window — is wrapped in a
//! [`tokio::time::timeout`], directly or through [`dial_until`]'s own
//! per-attempt and deadline bounds. Setup and teardown awaits (binding the
//! rendezvous server or an endpoint, closing a link, shutting the rendezvous
//! down) are loopback-local calls this file does not bound; a hang there
//! would be a bug in the library or the test harness, not the kind of
//! peer/network stall this suite is built to catch.
//! Checkable: `git grep -n 'await' iroh/sidecar/tests/rendezvous_mode.rs`.
//!
//! The other half of [DSC2-RDV-04] — that the publisher *keeps retrying
//! publication* on iroh's republish schedule after the server returns — is
//! iroh's own behaviour on a 5-minute `DEFAULT_REPUBLISH_INTERVAL` and is
//! **not asserted here**. This file asserts only the delivery half: an
//! established link keeps carrying frames after the rendezvous goes away.
//!
//! # The documented default (F2-D7)
//!
//! Adding this mode changes no default: with no flags the sidecar stays
//! `LookupMode::N0`, which is what development keeps using; a deployment that
//! must not publish its addresses to n0 runs `Rendezvous` against a server it
//! hosts itself, exactly as these tests do.

use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr},
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
    time::{Duration, Instant},
};

use computenet_iroh_sidecar::{
    EndpointId, Error, Link, LookupMode, SecretKey, SidecarConfig, SidecarEndpoint,
};
use iroh_dns_server::{
    config::{Config, MetricsConfig, RateLimitConfig},
    Server,
};
use url::Url;

/// Bounds every await that can block on a peer — an accept or a frame read —
/// so a hang there fails the test instead of wedging the run. See the module
/// doc for the awaits this file does not bound (loopback-local setup and
/// teardown).
const TIMEOUT: Duration = Duration::from_secs(30);

/// How long a single dial attempt is given before it is abandoned and retried.
const PER_ATTEMPT: Duration = Duration::from_secs(5);

/// The overall bound on [`dial_until`]'s retry loop.
const DIAL_DEADLINE: Duration = Duration::from_secs(30);

/// Pacing between dial attempts. This paces retries; it never stands in for an
/// assertion.
const POLL: Duration = Duration::from_millis(250);

/// How long the negative control waits before concluding no link is coming.
const NO_LINK_WINDOW: Duration = Duration::from_secs(15);

/// The zone the server answers `<endpointid>.<origin>` queries under — the
/// same default `src/bin/dns.rs` uses.
const ORIGIN: &str = "irohdns.example.";

/// Distinguishes the per-test data directories, so two tests in one process
/// never open the same redb file.
static DATA_DIR_SEQ: AtomicU64 = AtomicU64::new(0);

/// A self-hosted rendezvous on loopback: the pkarr HTTP route and the DNS
/// listener, both on ephemeral ports, no TLS, no metrics, no mainline DHT.
///
/// Returns the server (dropping it would take the rendezvous down), the pkarr
/// relay url clients publish to and resolve from, the DNS origin, and the
/// address of the DNS listener.
///
/// The `Server` must be shut down or leaked deliberately; `shutdown()` is what
/// test 2 uses to take the rendezvous away mid-exchange.
async fn spawn_dns() -> (Server, Url, String, SocketAddr) {
    let data_dir = unique_data_dir();
    std::fs::create_dir_all(&data_dir).expect("creating the test data dir");

    let server = Server::bind(loopback_config(data_dir))
        .await
        .expect("the rendezvous server binds on loopback");

    let http_addr = server
        .http_addr()
        .expect("a TLS-less dns server reports its plain-HTTP address");
    let dns_addr = server.dns_addr();
    let pkarr: Url = format!("http://{http_addr}/pkarr")
        .parse()
        .expect("the server's own address is a url");

    (server, pkarr, ORIGIN.to_string(), dns_addr)
}

/// A temp directory unique to this process and this call, so nothing is shared
/// between tests running in the same binary.
fn unique_data_dir() -> PathBuf {
    std::env::temp_dir().join(format!(
        "computenet-rendezvous-test-{}-{}",
        std::process::id(),
        DATA_DIR_SEQ.fetch_add(1, Ordering::Relaxed)
    ))
}

/// The same configuration `src/bin/dns.rs`'s `loopback_config` builds, and for
/// the same reasons. It is duplicated rather than shared because the library
/// must not depend on `iroh-dns-server` and a `[[bin]]` cannot be imported; if
/// the binary's version changes, change this one with it.
///
/// All of `Config`'s sub-structs are `#[non_exhaustive]`, so this mutates
/// `Config::default()` field by field rather than constructing one.
fn loopback_config(data_dir: PathBuf) -> Config {
    let mut config = Config::default();

    let http = config
        .http
        .as_mut()
        .expect("Config::default() configures an HTTP listener");
    http.port = 0;
    http.bind_addr = Some(IpAddr::V4(Ipv4Addr::LOCALHOST));

    // No TLS: the pkarr route is plain HTTP on loopback.
    config.https = None;

    config.dns.port = 0;
    config.dns.bind_addr = Some(IpAddr::V4(Ipv4Addr::LOCALHOST));
    // The root zone "." must stay alongside the origin: the crate's static
    // authority is rooted at `Name::root()` and needs an SOA record there.
    config.dns.origins = vec![ORIGIN.to_string(), ".".to_string()];

    config.metrics = Some(MetricsConfig::disabled());
    // `mainline` stays `None`: no BitTorrent DHT fallback, so a rendezvous
    // here resolves only keys that were published to it (aas-D5) — which is
    // exactly what test 3 depends on.
    config.mainline = None;
    // The default rate limit is per peer IP (4/s, burst 2) and every loopback
    // client shares 127.0.0.1, so it would 429 the publishers in this file.
    config.pkarr_put_rate_limit = RateLimitConfig::Disabled;
    config.data_dir = Some(data_dir);

    config
}

/// An endpoint bound to loopback whose only route to a peer is the rendezvous
/// at `pkarr` / `dns`.
///
/// `relay: None` — no relay is configured at all, so the only thing that can
/// carry a link is the loopback IP address this endpoint publishes to the
/// rendezvous. `dns_nameserver: Some(dns)` points the endpoint's resolver
/// straight at the server's DNS half, which a loopback deployment needs
/// (F2-D8); the host's own nameservers know nothing of this zone.
async fn bind_rendezvous(pkarr: &Url, origin: &str, dns: SocketAddr) -> SidecarEndpoint {
    let endpoint = SidecarEndpoint::bind(SidecarConfig {
        lookup: LookupMode::Rendezvous {
            pkarr_relay: pkarr.clone(),
            dns_origin: origin.to_string(),
            dns_nameserver: Some(dns),
            relay: None,
        },
        bind_addrs: vec!["127.0.0.1:0".parse().expect("literal loopback addr")],
        ..Default::default()
    })
    .await
    .expect("bind against the self-hosted rendezvous");

    // Pinned per endpoint, not once per file: mDNS would be an ambient LAN
    // path to the peer and would spoil the positive test's argument.
    assert!(
        endpoint.mdns().is_none(),
        "no LAN enumeration is configured, so the rendezvous is the only route"
    );
    endpoint
}

/// Dials `peer` by its bare id, retrying under a deadline.
///
/// Publication to the rendezvous is asynchronous, so the first attempts are
/// expected to fail. The loop's shape is the decided one (F2-D12): each
/// attempt is bounded by `PER_ATTEMPT`, failures are paced by `POLL`, the
/// whole loop is bounded by `DIAL_DEADLINE`, and **the assertion is the
/// dial's outcome** — reaching the deadline panics with the last failure
/// rather than concluding anything from elapsed time.
async fn dial_until(dialler: &SidecarEndpoint, peer: EndpointId) -> Link {
    let deadline = Instant::now() + DIAL_DEADLINE;
    let mut attempts = 0u32;
    // Assigned by every iteration before it is read; the compiler proves it.
    let mut last;
    loop {
        attempts += 1;
        match tokio::time::timeout(PER_ATTEMPT, dialler.dial(peer)).await {
            Ok(Ok(link)) => return link,
            Ok(Err(e)) => last = describe_chain(&e),
            Err(_elapsed) => last = format!("no answer within {PER_ATTEMPT:?}"),
        }
        assert!(
            Instant::now() < deadline,
            "the bare-id dial never resolved through the rendezvous: \
             {attempts} attempts over {DIAL_DEADLINE:?}, last failure: {last}"
        );
        tokio::time::sleep(POLL).await;
    }
}

/// Accepts one inbound link and its first frame, on a task, so the dialler can
/// make progress meanwhile. Returns the link itself (not just what it saw), so
/// a caller can keep using it — which is what test 2 needs after the
/// rendezvous is gone.
fn accept_one_frame(listener: SidecarEndpoint) -> tokio::task::JoinHandle<(Link, Vec<u8>)> {
    tokio::spawn(async move {
        let mut link = tokio::time::timeout(TIMEOUT, listener.accept())
            .await
            .expect("accept did not time out")
            .expect("accept succeeded")
            .expect("endpoint still open");
        let frame = tokio::time::timeout(TIMEOUT, link.recv_frame())
            .await
            .expect("frame did not time out")
            .expect("frame read")
            .expect("stream not finished");
        (link, frame)
    })
}

/// Sends `payload` on `from` and asserts it arrives on `to` within [`TIMEOUT`].
async fn crosses(from: &mut Link, to: &mut Link, payload: &[u8], what: &str) {
    from.send_frame(payload).await.expect("frame sent");
    let frame = tokio::time::timeout(TIMEOUT, to.recv_frame())
        .await
        .unwrap_or_else(|_| panic!("{what}: the frame did not arrive within {TIMEOUT:?}"))
        .expect("frame read")
        .expect("stream not finished");
    assert_eq!(frame, payload, "{what}");
}

/// Renders an error and every `source()` behind it on one line. `Error`'s own
/// `Display` names only where the failure happened; test 3 needs the whole
/// chain to show what it does *not* say.
fn describe_chain(err: &(dyn std::error::Error + 'static)) -> String {
    let mut out = err.to_string();
    let mut cause = err.source();
    while let Some(c) = cause {
        out.push_str(": ");
        out.push_str(&c.to_string());
        cause = c.source();
    }
    out
}

/// How a dial that produced no link ended. The two variants are the two
/// shapes `relay_mode.rs`'s control also accepts.
#[derive(Debug)]
enum NoLink {
    /// No answer at all inside the window.
    TimedOut,
    /// An outright `Error::Dial`, with its whole source chain rendered.
    DialRefused(String),
}

/// Asserts `outcome` is not a link, and classifies how it failed.
fn no_link(
    outcome: std::result::Result<
        computenet_iroh_sidecar::Result<Link>,
        tokio::time::error::Elapsed,
    >,
    what: &str,
) -> NoLink {
    match outcome {
        Err(_elapsed) => NoLink::TimedOut,
        Ok(Err(e)) => {
            assert!(
                matches!(e, Error::Dial(_)),
                "{what}: a dial that resolved nothing fails in the dial, \
                 not elsewhere: {e:?}"
            );
            NoLink::DialRefused(describe_chain(&e))
        }
        Ok(Ok(link)) => panic!(
            "{what}: a link came up against a rendezvous that never learned \
             the peer, so the positive test does not prove the rendezvous \
             carried it: {:?}",
            link.remote()
        ),
    }
}

#[tokio::test]
async fn a_bare_id_dial_resolves_through_the_self_hosted_rendezvous() {
    // BS-02, [DSC2-RDV-03]. One rendezvous server; two endpoints that have
    // never heard of each other; a dial by bare id. See the module doc for why
    // nothing else could have carried this link.
    let (server, pkarr, origin, dns) = spawn_dns().await;

    let listener = bind_rendezvous(&pkarr, &origin, dns).await;
    let dialler = bind_rendezvous(&pkarr, &origin, dns).await;
    let listener_id = listener.id();
    let dialler_id = dialler.id();

    let accepting = accept_one_frame(listener.clone());

    // The whole test: an id, and nothing else. No `add_peer` anywhere.
    let mut dialled = dial_until(&dialler, listener_id).await;
    assert_eq!(
        dialled.remote(),
        listener_id,
        "link addresses the dialled peer"
    );

    dialled
        .send_frame(b"through the rendezvous")
        .await
        .expect("frame sent");

    let (mut accepted, frame) = accepting.await.expect("acceptor task finished");
    assert_eq!(
        accepted.remote(),
        dialler_id,
        "the acceptor sees the dialler"
    );
    assert_eq!(
        frame, b"through the rendezvous",
        "the frame crossed the link the rendezvous established"
    );

    // And back the other way, so the link is proven in both directions.
    crosses(
        &mut accepted,
        &mut dialled,
        b"and back again",
        "listener to dialler",
    )
    .await;

    dialler.close().await;
    listener.close().await;
    server.shutdown().await.expect("the rendezvous shuts down");
}

#[tokio::test]
async fn an_established_link_keeps_delivering_after_the_rendezvous_goes_away() {
    // [DSC2-RDV-04], delivery half. The rendezvous introduces peers; it does
    // not carry their traffic. Once the link exists it is a direct QUIC
    // connection between two loopback sockets, and killing the server must not
    // touch it.
    //
    // The republish half of RDV-04 is iroh's own behaviour on a 5-minute
    // interval and is deliberately not asserted here (module doc).
    let (server, pkarr, origin, dns) = spawn_dns().await;

    let listener = bind_rendezvous(&pkarr, &origin, dns).await;
    let dialler = bind_rendezvous(&pkarr, &origin, dns).await;
    let listener_id = listener.id();

    let accepting = accept_one_frame(listener.clone());
    let mut dialled = dial_until(&dialler, listener_id).await;
    dialled.send_frame(b"before").await.expect("frame sent");
    let (mut accepted, frame) = accepting.await.expect("acceptor task finished");
    assert_eq!(frame, b"before", "the link is up and carrying frames");

    // The rendezvous goes away, in-process and completely: no pkarr route, no
    // DNS listener, no process to restart it.
    server.shutdown().await.expect("the rendezvous shuts down");

    crosses(
        &mut dialled,
        &mut accepted,
        b"after the rendezvous died",
        "dialler to listener after shutdown",
    )
    .await;
    crosses(
        &mut accepted,
        &mut dialled,
        b"and still the other way",
        "listener to dialler after shutdown",
    )
    .await;

    dialler.close().await;
    listener.close().await;
}

#[tokio::test]
async fn a_dial_fails_against_a_rendezvous_that_never_learned_the_peer_and_looks_like_absence() {
    // BS-13, [DSC2-NV-02]. This test PROVES A LIMITATION; it is not a
    // detection and must never be softened into one.
    //
    // Two rendezvous servers. The listener publishes to A; the dialler
    // resolves from B, which therefore never learned the listener's key — the
    // *withholding* case, as it appears from the dialler's side: a rendezvous
    // that has a record and declines to serve it is indistinguishable from
    // this one, which never had it. The dialler then also dials a key that was
    // generated here and published nowhere at all — the *absence* case.
    //
    // The assertion is that those two outcomes are the same: the same failure
    // shape, and an error chain that names nothing about the other server and
    // nothing about withholding — because there is no such signal to name.
    // A rendezvous cannot be audited from the dialler's side; that is the
    // recorded limitation.
    let (server_a, pkarr_a, origin_a, dns_a) = spawn_dns().await;
    let (server_b, pkarr_b, origin_b, dns_b) = spawn_dns().await;
    assert_ne!(pkarr_a, pkarr_b, "two distinct rendezvous servers");
    assert_ne!(dns_a, dns_b, "with distinct DNS listeners");

    let listener = bind_rendezvous(&pkarr_a, &origin_a, dns_a).await;
    let dialler = bind_rendezvous(&pkarr_b, &origin_b, dns_b).await;
    let listener_id = listener.id();

    // The listener is genuinely listening: an accept is pending throughout, so
    // "no link" cannot be "nobody was there".
    let listening = listener.clone();
    let accepting = tokio::spawn(async move { listening.accept().await });

    // A key that exists only in this line: published to no server anywhere.
    let never_published = SecretKey::generate().public();
    assert_ne!(never_published, listener_id, "two distinct ids");

    let started = Instant::now();
    let withheld = no_link(
        tokio::time::timeout(NO_LINK_WINDOW, dialler.dial(listener_id)).await,
        "withholding: the listener published to A, the dialler resolves from B",
    );
    let absent = no_link(
        tokio::time::timeout(NO_LINK_WINDOW, dialler.dial(never_published)).await,
        "absence: a key nobody ever published",
    );
    eprintln!(
        "[DSC2-NV-02] both dials settled in {:?}: withheld={withheld:?} absent={absent:?}",
        started.elapsed()
    );

    // The limitation itself: the two cases are the same failure.
    assert_eq!(
        std::mem::discriminant(&withheld),
        std::mem::discriminant(&absent),
        "a withheld peer and an absent one fail the same way, which is the \
         point: {withheld:?} vs {absent:?}"
    );

    // And nothing in either error's chain distinguishes them. This is not a
    // wish about wording — the dialler holds no information that could
    // distinguish them, so any such word would be a fabrication.
    //
    // `outcome` can also be `NoLink::TimedOut` (the dial never answered inside
    // `NO_LINK_WINDOW`, rather than erroring outright): that arm carries no
    // chain to check, so the substring assertions below are skipped for it.
    // If they were skipped for *both* cases this loop would report `ok` while
    // asserting nothing about [DSC2-NV-02]'s error-chain half, so the count is
    // checked afterwards rather than trusted to the loop alone.
    let mut chains_checked = 0u32;
    for (case, outcome) in [("withheld", &withheld), ("absent", &absent)] {
        let NoLink::DialRefused(chain) = outcome else {
            continue; // nothing was said at all, which says even less
        };
        chains_checked += 1;
        let lower = chain.to_lowercase();
        for forbidden in ["withheld", "withhold", "omitted", "censor"] {
            assert!(
                !lower.contains(forbidden),
                "{case}: the dialler cannot know this, so its error must not \
                 say {forbidden:?}: {chain}"
            );
        }
        // Nor does it name *which* key failed. Observed 2026-09-21 on
        // darwin/arm64: the two chains were byte-identical. That exact
        // equality is not asserted — the chain embeds a retry count that iroh
        // is free to vary — but the ids' absence from it is, because it is
        // what makes the two cases textually interchangeable.
        for id in [listener_id.to_string(), never_published.to_string()] {
            assert!(
                !chain.contains(&id),
                "{case}: the error names the key it failed on ({id}), so the \
                 two cases would be textually distinguishable: {chain}"
            );
        }
        for other in [
            pkarr_a.as_str(),
            &dns_a.to_string(),
            pkarr_b.as_str(),
            &dns_b.to_string(),
        ] {
            assert!(
                !chain.contains(other),
                "{case}: the error names a rendezvous server ({other}), which \
                 would be a signal this test claims does not exist: {chain}"
            );
        }
    }
    assert!(
        chains_checked > 0,
        "[DSC2-NV-02]: both outcomes were NoLink::TimedOut, so none of the \
         withheld/withhold/omitted/censor or id/server substring assertions \
         ran for either case — the error-chain half of this test asserted \
         nothing: withheld={withheld:?} absent={absent:?}"
    );

    accepting.abort();
    dialler.close().await;
    listener.close().await;
    server_a.shutdown().await.expect("server A shuts down");
    server_b.shutdown().await.expect("server B shuts down");
}
