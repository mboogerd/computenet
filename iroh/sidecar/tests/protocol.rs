//! The local-socket protocol, end to end, spoken by a client that knows nothing
//! about the crate's internals beyond `PROTOCOL.md`.
//!
//! Every assertion here is an observable outcome of the documented contract: the
//! bytes on the socket, the ids they carry, the frames that come back, and the
//! link-up / link-down notifications. Nothing reaches into the server's state.

use std::{net::SocketAddr, time::Duration};

use computenet_iroh_sidecar::{
    protocol::{
        encode_hex, kind, read_message, write_message, Message, CONTROL_LINK, DIRECTION_INBOUND,
        DIRECTION_OUTBOUND,
    },
    serve, SidecarConfig, SidecarEndpoint,
};
use tokio::{
    io::{AsyncRead, AsyncWrite},
    net::{
        tcp::{OwnedReadHalf, OwnedWriteHalf},
        TcpListener, TcpStream,
    },
};

/// Bounds every await, so a protocol hang fails the test instead of wedging the
/// run.
const TIMEOUT: Duration = Duration::from_secs(30);

/// Smaller than the length prefix itself, and past 64 KiB: the two sizes that
/// catch a receiver which lost message boundaries.
const SMALL: usize = 3;
const LARGE: usize = 70_000;

fn payload(len: usize, seed: u8) -> Vec<u8> {
    (0..len)
        .map(|i| (i as u8).wrapping_mul(31).wrapping_add(seed))
        .collect()
}

/// A host-side client that speaks only what `PROTOCOL.md` documents.
struct Host<R, W> {
    name: &'static str,
    reader: R,
    writer: W,
}

impl Host<OwnedReadHalf, OwnedWriteHalf> {
    async fn connect(name: &'static str, addr: SocketAddr) -> Self {
        let socket = TcpStream::connect(addr).await.expect("connect to sidecar");
        let (reader, writer) = socket.into_split();
        Host {
            name,
            reader,
            writer,
        }
    }
}

impl<R: AsyncRead + Unpin, W: AsyncWrite + Unpin> Host<R, W> {
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

    /// Reads the next message and asserts its kind and link id — so message
    /// order is asserted too, not just presence.
    async fn expect(&mut self, kind: u8, link: u64) -> Vec<u8> {
        let msg = self.recv().await;
        assert_eq!(
            (msg.kind, msg.link),
            (kind, link),
            "{}: expected kind 0x{kind:02x} on link {link}, got kind 0x{:02x} on link {} with payload {:?}",
            self.name,
            msg.kind,
            msg.link,
            String::from_utf8_lossy(&msg.payload)
        );
        msg.payload
    }

    async fn node_id(&mut self) -> Vec<u8> {
        self.send(Message::control(kind::GET_ID, Vec::new())).await;
        let id = self.expect(kind::ID, CONTROL_LINK).await;
        assert_eq!(id.len(), 32, "{}: an endpoint id is 32 bytes", self.name);
        id
    }
}

/// Binds a sidecar endpoint offline (no relay, no DNS, loopback only) and serves
/// its protocol on an ephemeral loopback TCP port — exactly what the binary does
/// around `serve`, minus the process boundary.
async fn spawn_sidecar() -> SocketAddr {
    let endpoint = SidecarEndpoint::bind(SidecarConfig::offline_loopback())
        .await
        .expect("bind sidecar endpoint");
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
    addr
}

#[tokio::test]
async fn the_documented_protocol_carries_a_link_from_dial_to_link_down() {
    let a = spawn_sidecar().await;
    let b = spawn_sidecar().await;
    let mut host_a = Host::connect("A", a).await;
    let mut host_b = Host::connect("B", b).await;

    // Report own NodeId.
    let a_id = host_a.node_id().await;
    let b_id = host_b.node_id().await;
    assert_ne!(a_id, b_id, "two sidecars have distinct endpoint ids");

    // Listen, and learn the addresses another sidecar can reach us on.
    host_a
        .send(Message::control(kind::LISTEN, Vec::new()))
        .await;
    let a_addrs = host_a.expect(kind::LISTENING, CONTROL_LINK).await;
    let a_addrs = String::from_utf8(a_addrs).expect("LISTENING payload is UTF-8");
    assert!(
        a_addrs.split(',').all(|s| s.parse::<SocketAddr>().is_ok()) && !a_addrs.is_empty(),
        "LISTENING reports parseable socket addresses, got {a_addrs:?}"
    );

    // Introduce A to B by the documented ADD_PEER payload: id bytes then addrs.
    let mut add_peer = a_id.clone();
    add_peer.extend_from_slice(a_addrs.as_bytes());
    host_b
        .send(Message::control(kind::ADD_PEER, add_peer))
        .await;
    assert_eq!(
        host_b.expect(kind::PEER_ADDED, CONTROL_LINK).await,
        a_id,
        "PEER_ADDED echoes the peer that was added"
    );

    // Dial by NodeId on a host-chosen odd link id.
    const OUT: u64 = 1;
    host_b
        .send(Message::new(kind::DIAL, OUT, a_id.clone()))
        .await;
    let up_b = host_b.expect(kind::LINK_UP, OUT).await;
    assert_eq!(
        &up_b[..32],
        &a_id[..],
        "the link addresses the dialled peer"
    );
    assert_eq!(
        up_b[32], DIRECTION_OUTBOUND,
        "the dialling side reports an outbound link"
    );

    // A observes the same link coming up, on a sidecar-allocated even id.
    let inbound = host_a.recv().await;
    assert_eq!(inbound.kind, kind::LINK_UP, "A sees the link come up");
    assert_eq!(
        inbound.link % 2,
        0,
        "sidecar-allocated inbound link ids are even, got {}",
        inbound.link
    );
    assert_ne!(inbound.link, CONTROL_LINK, "0 is reserved for control");
    assert_eq!(&inbound.payload[..32], &b_id[..], "A sees B as the remote");
    assert_eq!(
        inbound.payload[32], DIRECTION_INBOUND,
        "the accepting side reports an inbound link"
    );
    let inn = inbound.link;

    // Frames both ways, boundaries preserved past 64 KiB.
    let small = payload(SMALL, 7);
    let large = payload(LARGE, 11);
    host_b
        .send(Message::new(kind::DATA, OUT, small.clone()))
        .await;
    host_b
        .send(Message::new(kind::DATA, OUT, large.clone()))
        .await;

    let first = host_a.expect(kind::DATA, inn).await;
    let second = host_a.expect(kind::DATA, inn).await;
    assert_eq!(first.len(), SMALL, "first frame is exactly {SMALL} bytes");
    assert_eq!(second.len(), LARGE, "second frame is exactly {LARGE} bytes");
    assert_eq!(first, small, "first frame payload preserved");
    assert_eq!(second, large, "second frame payload preserved");

    let reply = payload(4321, 23);
    host_a
        .send(Message::new(kind::DATA, inn, reply.clone()))
        .await;
    assert_eq!(
        host_b.expect(kind::DATA, OUT).await,
        reply,
        "the reply comes back on the dialling side's own link id"
    );

    // Close the link; both ends observe link-down, exactly once each.
    host_b
        .send(Message::new(kind::CLOSE_LINK, OUT, Vec::new()))
        .await;
    let down_b = host_b.expect(kind::LINK_DOWN, OUT).await;
    assert!(
        !down_b.is_empty() && String::from_utf8(down_b).is_ok(),
        "LINK_DOWN carries a UTF-8 reason"
    );
    let down_a = host_a.expect(kind::LINK_DOWN, inn).await;
    assert!(
        String::from_utf8(down_a).is_ok(),
        "the accepting side's LINK_DOWN reason is UTF-8 too"
    );

    // LINK_DOWN is terminal: the id no longer names a link.
    host_b
        .send(Message::new(kind::DATA, OUT, vec![1, 2, 3]))
        .await;
    let refused = host_b.expect(kind::ERROR, OUT).await;
    assert!(
        String::from_utf8_lossy(&refused).contains("no such link"),
        "sending on a downed link is refused, got {:?}",
        String::from_utf8_lossy(&refused)
    );

    host_a
        .send(Message::control(kind::SHUTDOWN, Vec::new()))
        .await;
    host_b
        .send(Message::control(kind::SHUTDOWN, Vec::new()))
        .await;
}

#[tokio::test]
async fn malformed_requests_are_refused_without_taking_the_connection_down() {
    let addr = spawn_sidecar().await;
    let mut host = Host::connect("S", addr).await;
    let id = host.node_id().await;

    // An even DIAL id is the sidecar's to allocate, not the host's.
    host.send(Message::new(kind::DIAL, 2, id.clone())).await;
    let e = host.expect(kind::ERROR, 2).await;
    assert!(
        String::from_utf8_lossy(&e).contains("odd and non-zero"),
        "even DIAL ids are refused, got {:?}",
        String::from_utf8_lossy(&e)
    );

    // A DIAL payload that is not an endpoint id.
    host.send(Message::new(kind::DIAL, 3, vec![0u8; 5])).await;
    assert!(
        String::from_utf8_lossy(&host.expect(kind::ERROR, 3).await).contains("32-byte endpoint id")
    );

    // An undocumented kind.
    host.send(Message::control(0x7f, Vec::new())).await;
    assert!(
        String::from_utf8_lossy(&host.expect(kind::ERROR, CONTROL_LINK).await)
            .contains("unknown message kind 0x7f")
    );

    // The connection still works.
    assert_eq!(host.node_id().await, id, "the connection survived");
    host.send(Message::control(kind::SHUTDOWN, Vec::new()))
        .await;
}

#[tokio::test]
async fn the_binary_announces_its_port_and_node_id_on_one_stdout_line() {
    use tokio::io::{AsyncBufReadExt, BufReader};

    let mut child = tokio::process::Command::new(env!("CARGO_BIN_EXE_computenet-iroh-sidecar"))
        .arg("--offline")
        .stdout(std::process::Stdio::piped())
        .spawn()
        .expect("spawn the sidecar binary");

    let stdout = child.stdout.take().expect("piped stdout");
    let mut lines = BufReader::new(stdout).lines();
    let line = tokio::time::timeout(TIMEOUT, lines.next_line())
        .await
        .expect("handshake line did not time out")
        .expect("reading the handshake line")
        .expect("the binary wrote a handshake line");

    // {"port":<u16>,"nodeId":"<64 hex>"}
    let port: u16 = line
        .split("\"port\":")
        .nth(1)
        .and_then(|rest| rest.split(',').next())
        .and_then(|n| n.trim().parse().ok())
        .unwrap_or_else(|| panic!("no numeric port in handshake line {line:?}"));
    let node_id = line
        .split("\"nodeId\":\"")
        .nth(1)
        .and_then(|rest| rest.split('"').next())
        .unwrap_or_else(|| panic!("no nodeId in handshake line {line:?}"))
        .to_string();
    assert_eq!(node_id.len(), 64, "nodeId is 32 bytes of hex: {line:?}");

    let mut host = Host::connect("bin", SocketAddr::from(([127, 0, 0, 1], port))).await;
    assert_eq!(
        encode_hex(&host.node_id().await),
        node_id,
        "GET_ID over the announced port reports the announced nodeId"
    );

    // Exactly one line: the next read ends with the process, not a second line.
    host.send(Message::control(kind::SHUTDOWN, Vec::new()))
        .await;
    let next = tokio::time::timeout(TIMEOUT, lines.next_line())
        .await
        .expect("second stdout read did not time out")
        .expect("reading stdout");
    assert_eq!(next, None, "the binary wrote exactly one stdout line");

    let status = tokio::time::timeout(TIMEOUT, child.wait())
        .await
        .expect("the binary exited")
        .expect("waiting on the binary");
    assert!(status.success(), "SHUTDOWN ends the process cleanly");
}
