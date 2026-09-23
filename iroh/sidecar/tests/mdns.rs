//! `PROTOCOL.md` §5, asserted: two `--offline --mdns` sidecars on loopback find
//! each other over mDNS, each host connection reads `PEER_DISCOVERED` naming
//! the other, and a **bare `DIAL`** of that id — with no `ADD_PEER` anywhere —
//! comes up.
//!
//! # Why this test can skip
//!
//! It needs real multicast **delivery**, which is not a property of the crate
//! under test: a host can refuse it (macOS' Local Network permission denies the
//! `sendto` with `No route to host`, observed on `MacBoo` 2026-09-19,
//! `ne2oh-B6`), and the refusal surfaces asynchronously inside
//! `swarm-discovery`'s actor rather than at `build()` — so neither a successful
//! `MdnsAddressLookup::builder().build()` nor an interface count predicts it.
//!
//! So the gate is a **delivery self-test** ([`multicast_delivery`]) rather than
//! either of those, and its failure prints a `SKIPPED` line naming the reason
//! and passes. `[DSC2-NV-01]`: this test must never fail for the host's lack of
//! multicast, and it is not the only evidence for any rule — the `Expired` →
//! `PEER_EXPIRED` mapping and the self-filter are pinned network-free by
//! `src/server.rs`'s `forwarding_feeds_the_lookup_before_it_announces_the_peer`,
//! and this file deliberately does **not** assert `PEER_EXPIRED` (the real
//! expiry is 30–43 s per run, `ne2oh-B5`).
//!
//! # Evidence this test's body actually ran
//!
//! A plain `cargo test`'s `test result` line does NOT distinguish an executed
//! run from a skipped one: libtest captures a PASSING test's stdout/stderr
//! and discards the capture, and a skip (`eprintln!` below, then `return`) IS
//! a pass — so the `SKIPPED tests/mdns.rs: ...` line never reaches a captured
//! log either way, and its absence proves nothing (computenet-2wmp5).
//!
//! CI's `iroh-sidecar` lane (ubuntu-latest, `.github/workflows/iroh-sidecar.yml`)
//! runs `cargo test -- --nocapture` instead, so the `SKIPPED tests/mdns.rs`
//! line survives into the job log when the gate refuses, and a following
//! step reddens the job if that line appears at all — every run observed so
//! far has delivered real multicast in that lane, so a skip there is treated
//! as a CI regression rather than an accepted downgrade. That assertion step
//! does not change this test: a developer machine that genuinely lacks
//! multicast still gets a passing skip, per `[DSC2-NV-01]`.

use std::{
    net::{Ipv4Addr, SocketAddr, UdpSocket},
    time::Duration,
};

use computenet_iroh_sidecar::{
    protocol::{kind, read_message, write_message, Message, CONTROL_LINK},
    serve, LookupMode, SidecarConfig, SidecarEndpoint,
};
use tokio::net::{
    tcp::{OwnedReadHalf, OwnedWriteHalf},
    TcpListener, TcpStream,
};

/// How long the two sidecars are given to see each other. mDNS announcement is
/// prompt on a working interface; this is a wedge bound, not an expectation.
const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(30);

/// Bounds every individual socket await.
const TIMEOUT: Duration = Duration::from_secs(30);

/// A multicast group in the locally-scoped administrative range, picked so a
/// probe cannot be confused with mDNS traffic on 224.0.0.251.
const PROBE_GROUP: Ipv4Addr = Ipv4Addr::new(239, 255, 42, 42);

/// Whether this host actually **delivers** a multicast datagram to a socket
/// that joined the group — the property the test needs and the one no API on
/// the crate reports.
///
/// Loopback delivery is enabled explicitly, so a machine with no LAN peer still
/// passes the gate; what it cannot survive is the OS refusing the send outright
/// (`ne2oh-B6`).
fn multicast_delivery() -> Result<(), String> {
    let receiver = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)))
        .map_err(|e| format!("binding the receiver failed: {e}"))?;
    let port = receiver
        .local_addr()
        .map_err(|e| format!("reading the receiver's port failed: {e}"))?
        .port();
    receiver
        .join_multicast_v4(&PROBE_GROUP, &Ipv4Addr::UNSPECIFIED)
        .map_err(|e| format!("joining {PROBE_GROUP} failed: {e}"))?;
    receiver
        .set_read_timeout(Some(Duration::from_secs(2)))
        .map_err(|e| format!("setting the read timeout failed: {e}"))?;

    let sender = UdpSocket::bind(SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0)))
        .map_err(|e| format!("binding the sender failed: {e}"))?;
    sender
        .set_multicast_loop_v4(true)
        .map_err(|e| format!("enabling multicast loopback failed: {e}"))?;
    sender
        .send_to(b"cn", SocketAddr::from((PROBE_GROUP, port)))
        .map_err(|e| format!("sending to {PROBE_GROUP}:{port} failed: {e}"))?;

    let mut buf = [0u8; 8];
    match receiver.recv_from(&mut buf) {
        Ok((n, _)) if &buf[..n] == b"cn" => Ok(()),
        Ok((n, from)) => Err(format!("the receiver got {n} unexpected bytes from {from}")),
        Err(e) => Err(format!("nothing was delivered within 2 s: {e}")),
    }
}

/// The same slim host client `tests/protocol.rs` uses. The two are duplicated
/// rather than shared because integration test files do not share code without
/// a `tests/common` module, which is outside this task's claim.
struct Host {
    name: &'static str,
    reader: OwnedReadHalf,
    writer: OwnedWriteHalf,
}

impl Host {
    async fn connect(name: &'static str, addr: SocketAddr) -> Self {
        let socket = TcpStream::connect(addr).await.expect("connect to sidecar");
        let (reader, writer) = socket.into_split();
        Host {
            name,
            reader,
            writer,
        }
    }

    async fn send(&mut self, msg: Message) {
        tokio::time::timeout(TIMEOUT, write_message(&mut self.writer, &msg))
            .await
            .unwrap_or_else(|_| panic!("{}: writing kind 0x{:02x} timed out", self.name, msg.kind))
            .unwrap_or_else(|e| {
                panic!("{}: writing kind 0x{:02x} failed: {e}", self.name, msg.kind)
            });
    }

    async fn recv(&mut self) -> Message {
        tokio::time::timeout(TIMEOUT, read_message(&mut self.reader))
            .await
            .unwrap_or_else(|_| panic!("{}: reading the next message timed out", self.name))
            .unwrap_or_else(|e| panic!("{}: reading the next message failed: {e}", self.name))
            .unwrap_or_else(|| panic!("{}: the sidecar closed the socket", self.name))
    }

    async fn expect(&mut self, kind: u8, link: u64) -> Vec<u8> {
        let msg = self.recv().await;
        assert_eq!(
            (msg.kind, msg.link),
            (kind, link),
            "{}: expected kind 0x{kind:02x} on link {link}, got kind 0x{:02x} on link {}",
            self.name,
            msg.kind,
            msg.link
        );
        msg.payload
    }

    /// Reads until a message of `kind` on `link` arrives or `bound` elapses.
    ///
    /// Order is deliberately not asserted here: discovery events are
    /// unsolicited and a peer whose addresses change is announced again, so a
    /// second `PEER_DISCOVERED` may legally arrive between a `DIAL` and its
    /// `LINK_UP`.
    async fn answer_within(&mut self, bound: Duration, kind: u8, link: u64) -> Option<Vec<u8>> {
        let deadline = tokio::time::Instant::now() + bound;
        loop {
            let left = deadline.saturating_duration_since(tokio::time::Instant::now());
            if left.is_zero() {
                return None;
            }
            match tokio::time::timeout(left, read_message(&mut self.reader)).await {
                Err(_) => return None,
                Ok(Ok(Some(msg))) if (msg.kind, msg.link) == (kind, link) => {
                    return Some(msg.payload)
                }
                Ok(Ok(Some(_))) => continue,
                Ok(Ok(None)) => panic!("{}: the sidecar closed the socket", self.name),
                Ok(Err(e)) => panic!("{}: reading the next message failed: {e}", self.name),
            }
        }
    }

    /// Reads until a `PEER_DISCOVERED` naming `peer` arrives or `bound`
    /// elapses, returning its address text. Every `PEER_DISCOVERED` seen on the
    /// way is checked against `own`, so a sidecar announcing *itself* fails the
    /// test rather than being skipped past.
    async fn discovered_within(
        &mut self,
        bound: Duration,
        peer: &[u8],
        own: &[u8],
    ) -> Option<String> {
        let deadline = tokio::time::Instant::now() + bound;
        loop {
            let left = deadline.saturating_duration_since(tokio::time::Instant::now());
            if left.is_zero() {
                return None;
            }
            let msg = match tokio::time::timeout(left, read_message(&mut self.reader)).await {
                Err(_) => return None,
                Ok(Ok(Some(msg))) => msg,
                Ok(Ok(None)) => panic!("{}: the sidecar closed the socket", self.name),
                Ok(Err(e)) => panic!("{}: reading the next message failed: {e}", self.name),
            };
            if msg.kind != kind::PEER_DISCOVERED {
                continue;
            }
            assert_eq!(
                msg.link, CONTROL_LINK,
                "{}: PEER_DISCOVERED is a control message",
                self.name
            );
            assert!(
                msg.payload.len() >= 32,
                "{}: PEER_DISCOVERED carries a 32-byte id",
                self.name
            );
            assert_ne!(
                &msg.payload[..32],
                own,
                "{}: the sidecar announced its OWN id",
                self.name
            );
            if &msg.payload[..32] == peer {
                return Some(String::from_utf8_lossy(&msg.payload[32..]).into_owned());
            }
        }
    }
}

/// An `--offline --mdns` sidecar, served on an ephemeral loopback TCP port.
/// `None` when the mDNS lookup did not bind on this host.
async fn spawn_mdns_sidecar() -> Option<(SocketAddr, Vec<u8>)> {
    let endpoint = SidecarEndpoint::bind(SidecarConfig {
        lookup: LookupMode::Offline,
        mdns: true,
        bind_addrs: vec!["127.0.0.1:0".parse().expect("literal loopback addr")],
        ..Default::default()
    })
    .await
    .expect("bind sidecar endpoint");
    endpoint.mdns()?;
    let id = endpoint.id().as_bytes().to_vec();

    let listener = TcpListener::bind(SocketAddr::from(([127, 0, 0, 1], 0)))
        .await
        .expect("bind loopback socket");
    let addr = listener.local_addr().expect("loopback port");
    tokio::spawn(async move {
        while let Ok((socket, _)) = listener.accept().await {
            let endpoint = endpoint.clone();
            tokio::spawn(async move {
                let _ = serve(endpoint, socket).await;
            });
        }
    });
    Some((addr, id))
}

#[tokio::test]
async fn two_mdns_sidecars_discover_each_other_and_dial_without_add_peer() {
    if let Err(reason) = multicast_delivery() {
        eprintln!("SKIPPED tests/mdns.rs: multicast delivery unavailable on this host: {reason}");
        return;
    }

    let Some((addr_a, id_a)) = spawn_mdns_sidecar().await else {
        eprintln!("SKIPPED tests/mdns.rs: the mDNS address lookup did not bind on this host");
        return;
    };
    let Some((addr_b, id_b)) = spawn_mdns_sidecar().await else {
        eprintln!("SKIPPED tests/mdns.rs: the mDNS address lookup did not bind on this host");
        return;
    };

    let mut host_a = Host::connect("host→A", addr_a).await;
    let mut host_b = Host::connect("host→B", addr_b).await;

    // A accepts, so B's later bare DIAL has something to reach.
    host_a
        .send(Message::control(kind::LISTEN, Vec::new()))
        .await;
    let a_sockets = String::from_utf8(host_a.expect(kind::LISTENING, CONTROL_LINK).await)
        .expect("LISTENING is UTF-8");

    for host in [&mut host_a, &mut host_b] {
        host.send(Message::control(kind::WATCH_PEERS, Vec::new()))
            .await;
        assert!(
            host.expect(kind::WATCHING, CONTROL_LINK).await.is_empty(),
            "WATCHING carries no payload"
        );
    }

    let b_saw_a = host_b
        .discovered_within(DISCOVERY_TIMEOUT, &id_a, &id_b)
        .await
        .unwrap_or_else(|| {
            panic!("B never read a PEER_DISCOVERED naming A within {DISCOVERY_TIMEOUT:?}")
        });
    let a_bound = a_sockets
        .split(',')
        .next()
        .expect("A is bound to at least one socket");
    assert!(
        b_saw_a.contains(a_bound),
        "the discovered address text {b_saw_a:?} should carry A's bound socket {a_bound:?}"
    );

    let a_saw_b = host_a
        .discovered_within(DISCOVERY_TIMEOUT, &id_b, &id_a)
        .await
        .unwrap_or_else(|| {
            panic!("A never read a PEER_DISCOVERED naming B within {DISCOVERY_TIMEOUT:?}")
        });
    assert!(
        !a_saw_b.is_empty(),
        "the discovered address text for B should not be empty"
    );

    // The claim: no ADD_PEER was ever sent on either connection, and the dial
    // resolves anyway, because the sidecar fed its own lookup before it
    // announced the peer (`PROTOCOL.md` §3, PEER_DISCOVERED).
    host_b.send(Message::new(kind::DIAL, 1, id_a.clone())).await;
    let up_b = host_b
        .answer_within(TIMEOUT, kind::LINK_UP, 1)
        .await
        .expect("B's bare DIAL of A came up with no ADD_PEER");
    assert_eq!(&up_b[..32], &id_a[..], "B's LINK_UP names A");

    let up_a = host_a
        .answer_within(TIMEOUT, kind::LINK_UP, 2)
        .await
        .expect("A accepted B's inbound link");
    assert_eq!(&up_a[..32], &id_b[..], "A's LINK_UP names B");

    host_a
        .send(Message::control(kind::SHUTDOWN, Vec::new()))
        .await;
    host_b
        .send(Message::control(kind::SHUTDOWN, Vec::new()))
        .await;
}
