//! Two sidecar endpoints meeting through a **self-hosted** relay, with the
//! dialler holding no direct address for the peer at all.
//!
//! The relay here is the same `iroh_relay::server::Server` that
//! `src/bin/relay.rs` runs, spawned in-process on an ephemeral loopback port
//! and served over plain HTTP. Nothing in this file reaches n0's public relay
//! or DNS/pkarr infrastructure (feature computenet-o0m3).
//!
//! # Why the positive test cannot pass for a lesser reason
//!
//! The dialler is taught the listener's whereabouts as exactly one
//! [`TransportAddr::Relay`] and nothing else — asserted, not merely intended.
//! Everything else that could carry a link is closed off by construction:
//!
//! * `LookupMode::Relay` binds on `presets::Minimal`, so the only address
//!   lookup service is the sidecar's own `MemoryLookup` (pinned by
//!   `endpoint.rs`'s `a_relay_config_binds_an_endpoint_over_that_relay_alone`).
//!   There is no DNS, no pkarr, no mDNS or other ambient local discovery.
//! * `MemoryLookup` holds only what `add_peer` put there, which here is the
//!   relay address alone.
//! * Both endpoints bind `127.0.0.1:0` with every other IP transport cleared,
//!   so neither advertises anything a peer could have guessed.
//!
//! and, empirically, by `a_link_does_not_come_up_when_the_two_sides_are_on_
//! different_relays`: same construction, same two live loopback endpoints, same
//! live relays — only the *rendezvous* is denied, and then no link forms. That
//! control is what turns the positive test from "a link came up while a relay
//! happened to be running" into "the relay performed the rendezvous".

use std::time::Duration;

use computenet_iroh_sidecar::{LookupMode, SidecarConfig, SidecarEndpoint};
use iroh::{EndpointAddr, RelayUrl, TransportAddr};
use iroh_relay::server::{RelayConfig, Server, ServerConfig};

/// Bounds every await so a hang fails the test instead of wedging the run.
const TIMEOUT: Duration = Duration::from_secs(30);

/// How long the negative control waits before concluding no link is coming.
/// Generous relative to the positive test, which completes in well under a
/// second on the same machine.
const NO_LINK_WINDOW: Duration = Duration::from_secs(15);

/// Spawns a relay on an ephemeral loopback port, exactly as
/// `src/bin/relay.rs` does, and returns it with the url clients dial it on.
///
/// The `Server` is returned rather than dropped because dropping it aborts the
/// relay's supervisor task.
async fn spawn_relay() -> (Server, RelayUrl) {
    let mut config = ServerConfig::default();
    config.relay = Some(RelayConfig::new(([127, 0, 0, 1], 0)));
    let server = Server::spawn(config).await.expect("the relay spawns");
    let addr = server
        .http_addr()
        .expect("a TLS-less relay reports its plain-HTTP address");
    let url: RelayUrl = format!("http://{addr}")
        .parse()
        .expect("the relay's own address is a url");
    (server, url)
}

/// An endpoint bound to loopback that knows exactly one relay and no address
/// lookup service beyond the sidecar's own `MemoryLookup`.
async fn bind_on(relay: &RelayUrl) -> SidecarEndpoint {
    SidecarEndpoint::bind(SidecarConfig {
        lookup: LookupMode::Relay(relay.clone()),
        bind_addrs: vec!["127.0.0.1:0".parse().expect("literal loopback addr")],
        ..Default::default()
    })
    .await
    .expect("bind against the self-hosted relay")
}

/// The peer address a dialler is given: the id, and a relay url. No IP
/// transport, which is the whole proof.
fn relay_only_addr(endpoint: &SidecarEndpoint, relay: &RelayUrl) -> EndpointAddr {
    let addr = EndpointAddr::from_parts(endpoint.id(), [TransportAddr::Relay(relay.clone())]);
    assert_eq!(addr.addrs.len(), 1, "exactly one transport address");
    assert!(
        addr.addrs.iter().all(TransportAddr::is_relay),
        "and it is a relay address: {:?}",
        addr.addrs
    );
    assert_eq!(
        addr.ip_addrs().count(),
        0,
        "no IP transport is supplied to the dialler"
    );
    addr
}

#[tokio::test]
async fn a_link_comes_up_through_the_self_hosted_relay_alone() {
    let (_relay, url) = spawn_relay().await;

    let listener = bind_on(&url).await;
    let dialler = bind_on(&url).await;
    let listener_id = listener.id();

    // The only thing the dialler ever learns about its peer.
    dialler.add_peer(relay_only_addr(&listener, &url));

    let accepted = tokio::spawn(async move {
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
        (link.remote(), frame)
    });

    let mut link = tokio::time::timeout(TIMEOUT, dialler.dial(listener_id))
        .await
        .expect("dial did not time out")
        .expect("dial through the relay succeeded");
    assert_eq!(
        link.remote(),
        listener_id,
        "link addresses the dialled peer"
    );

    link.send_frame(b"through the relay")
        .await
        .expect("frame sent");

    let (remote, frame) = accepted.await.expect("acceptor task finished");
    assert_eq!(remote, dialler.id(), "the acceptor sees the dialler");
    assert_eq!(
        frame, b"through the relay",
        "the frame crossed the relay-established link"
    );

    dialler.close().await;
}

#[tokio::test]
async fn a_link_does_not_come_up_when_the_two_sides_are_on_different_relays() {
    // The negative control for the test above. Two relays, both alive; two
    // endpoints, both alive on loopback, both relay-configured exactly as in
    // the positive test. The single difference is that the rendezvous cannot
    // happen: the listener is registered with relay A, and the dialler is
    // configured with — and told to reach the peer through — relay B.
    //
    // If any path other than the relay's rendezvous could establish this link
    // (an ambient loopback discovery, an IP transport leaking in through
    // `add_peer`, a fallback inside the endpoint), it would establish it here
    // too, and this test would fail.
    let (_relay_a, url_a) = spawn_relay().await;
    let (_relay_b, url_b) = spawn_relay().await;
    assert_ne!(url_a, url_b, "two distinct relays");

    let listener = bind_on(&url_a).await;
    let dialler = bind_on(&url_b).await;
    let listener_id = listener.id();

    // Same shape of address as the positive test — relay only — but naming the
    // relay the listener is not on.
    dialler.add_peer(relay_only_addr(&listener, &url_b));

    // The listener is genuinely listening: an accept is pending throughout.
    let accepting = tokio::spawn(async move { listener.accept().await });

    match tokio::time::timeout(NO_LINK_WINDOW, dialler.dial(listener_id)).await {
        Err(_elapsed) => {}         // no link within the window: the expected outcome
        Ok(Err(_dial_failed)) => {} // or an outright refusal: also no link
        Ok(Ok(link)) => panic!(
            "a link came up without a shared relay, so the positive test does \
             not prove relay rendezvous: {:?}",
            link.remote()
        ),
    }

    accepting.abort();
    dialler.close().await;
}
