//! The protocol loop: one host TCP connection driving one sidecar endpoint.
//!
//! This is the whole of the sidecar's behaviour, deliberately in the library
//! rather than the binary (feature decision egl.1-D2), so tests drive the
//! protocol in-process on a loopback listener without spawning anything. The
//! binary is a thin wrapper: bind, print the handshake line, call [`serve`].
//!
//! The message layout and every kind are documented in `PROTOCOL.md` and
//! mirrored in [`crate::protocol`].

use std::{
    collections::HashMap,
    io,
    net::SocketAddr,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex,
    },
};

use futures_util::{Stream, StreamExt};
use iroh::{EndpointAddr, TransportAddr};
use iroh_mdns_address_lookup::{DiscoveryEvent, MdnsAddressLookup};
use tokio::{
    io::{AsyncRead, AsyncWrite},
    net::TcpStream,
    sync::mpsc,
};

use crate::{
    endpoint::SidecarEndpoint,
    link::{Link, LinkWatcher, PendingLink},
    protocol::{
        endpoint_id_from_slice, kind, peer_discovered_payload, read_message, write_message,
        Message, CONTROL_LINK, DIRECTION_INBOUND, DIRECTION_OUTBOUND,
    },
};

/// Why [`serve`] returned.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ServeOutcome {
    /// The host closed the socket.
    Disconnected,
    /// The host sent [`kind::SHUTDOWN`].
    Shutdown,
}

/// How many messages may sit in the socket writer's queue, and how many frames
/// in one link's send queue.
///
/// This is the sidecar's entire backpressure story, and the two queues answer a
/// full buffer differently on purpose:
///
/// * the socket writer's queue **waits** — a host that stops reading its socket
///   eventually stalls the QUIC read loop that feeds it, rather than buffering
///   without limit;
/// * a link's send queue **refuses** — host `DATA` past the bound is answered
///   with `ERROR` on that link and not sent, because waiting on it would block
///   the single host message loop for every link at once (computenet-3gij).
///
/// The sidecar's half of that ends at the `ERROR`: it keeps the link registered
/// and keeps serving. What the *host* owes in reply is `CLOSE_LINK` on that link
/// — an `ERROR` on an established link is terminal for it (`PROTOCOL.md` §2,
/// Backpressure; computenet-ey4v). The rule lives on the host side rather than
/// here on purpose: the sidecar closing the link itself would make a flood
/// indistinguishable from a peer disconnecting, and would take away the one
/// property `tests/protocol.rs` pins about a flooded link — that it, and every
/// other link on the connection, still answers `CLOSE_LINK` and `SHUTDOWN`.
const QUEUE_DEPTH: usize = 256;

struct LinkHandle {
    frames: mpsc::Sender<Vec<u8>>,
    watcher: LinkWatcher,
}

type Links = Arc<Mutex<HashMap<u64, LinkHandle>>>;

/// Serves one host connection to completion.
pub async fn serve(endpoint: SidecarEndpoint, socket: TcpStream) -> io::Result<ServeOutcome> {
    let (reader, writer) = socket.into_split();
    serve_io(endpoint, reader, writer).await
}

/// [`serve`] over any reader/writer pair, so tests can drive it over something
/// other than a `TcpStream`.
pub async fn serve_io<R, W>(
    endpoint: SidecarEndpoint,
    mut reader: R,
    mut writer: W,
) -> io::Result<ServeOutcome>
where
    R: AsyncRead + Unpin,
    W: AsyncWrite + Unpin + Send + 'static,
{
    let (out, mut out_rx) = mpsc::channel::<Message>(QUEUE_DEPTH);
    let writer_task = tokio::spawn(async move {
        while let Some(msg) = out_rx.recv().await {
            if write_message(&mut writer, &msg).await.is_err() {
                break;
            }
        }
    });

    let links: Links = Arc::new(Mutex::new(HashMap::new()));
    let next_inbound = Arc::new(AtomicU64::new(1));
    let mut accepting: Option<tokio::task::JoinHandle<()>> = None;
    // The one peer-discovery forwarding task for this host connection, started
    // by the first WATCH_PEERS and aborted with the connection, exactly as
    // `accepting` is. `None` also stands for "this endpoint has no mDNS
    // lookup", which is why WATCH_PEERS cannot be answered from it.
    let mut watching: Option<tokio::task::JoinHandle<()>> = None;

    let outcome = loop {
        let msg = match read_message(&mut reader).await {
            Ok(Some(msg)) => msg,
            Ok(None) => break ServeOutcome::Disconnected,
            Err(e) => {
                let _ = out
                    .send(Message::control(
                        kind::ERROR,
                        format!("malformed message: {e}").into_bytes(),
                    ))
                    .await;
                break ServeOutcome::Disconnected;
            }
        };

        match msg.kind {
            kind::GET_ID => {
                send(
                    &out,
                    Message::control(kind::ID, endpoint.id().as_bytes().to_vec()),
                )
                .await;
            }
            kind::LISTEN => {
                if accepting.is_none() {
                    accepting = Some(spawn_accept_loop(
                        endpoint.clone(),
                        out.clone(),
                        links.clone(),
                        next_inbound.clone(),
                    ));
                }
                let addrs = endpoint
                    .bound_sockets()
                    .iter()
                    .map(SocketAddr::to_string)
                    .collect::<Vec<_>>()
                    .join(",");
                send(&out, Message::control(kind::LISTENING, addrs.into_bytes())).await;
            }
            kind::ADD_PEER => match parse_peer(&msg.payload) {
                Some(addr) => {
                    let id = addr.id;
                    endpoint.add_peer(addr);
                    send(
                        &out,
                        Message::control(kind::PEER_ADDED, id.as_bytes().to_vec()),
                    )
                    .await;
                }
                None => {
                    send(
                        &out,
                        Message::control(
                            kind::ERROR,
                            b"ADD_PEER payload is not a 32-byte endpoint id followed by a comma-separated socket address list".to_vec(),
                        ),
                    )
                    .await;
                }
            },
            kind::DIAL => {
                let id = msg.link;
                if id == CONTROL_LINK || id.is_multiple_of(2) {
                    send(
                        &out,
                        Message::new(
                            kind::ERROR,
                            id,
                            format!("DIAL link id must be odd and non-zero; got {id}").into_bytes(),
                        ),
                    )
                    .await;
                } else if links.lock().expect("links mutex").contains_key(&id) {
                    send(
                        &out,
                        Message::new(kind::ERROR, id, format!("link {id} is in use").into_bytes()),
                    )
                    .await;
                } else {
                    match endpoint_id_from_slice(&msg.payload) {
                        Some(peer) => {
                            let endpoint = endpoint.clone();
                            let out = out.clone();
                            let links = links.clone();
                            tokio::spawn(async move {
                                match endpoint.dial(peer).await {
                                    Ok(link) => {
                                        let watcher = link.watcher();
                                        let remote = link.remote();
                                        let frames_rx = announce_link(
                                            id,
                                            remote,
                                            DIRECTION_OUTBOUND,
                                            watcher.clone(),
                                            &out,
                                            &links,
                                        )
                                        .await;
                                        start_pumps(
                                            id,
                                            link,
                                            watcher,
                                            frames_rx,
                                            out.clone(),
                                            links,
                                        )
                                        .await;
                                    }
                                    Err(e) => {
                                        send(
                                            &out,
                                            Message::new(
                                                kind::ERROR,
                                                id,
                                                format!("dial failed: {e}").into_bytes(),
                                            ),
                                        )
                                        .await
                                    }
                                }
                            });
                        }
                        None => {
                            send(
                                &out,
                                Message::new(
                                    kind::ERROR,
                                    id,
                                    b"DIAL payload is not a 32-byte endpoint id".to_vec(),
                                ),
                            )
                            .await;
                        }
                    }
                }
            }
            kind::DATA => {
                let frames = links
                    .lock()
                    .expect("links mutex")
                    .get(&msg.link)
                    .map(|h| h.frames.clone());
                match frames {
                    // `try_send`, never `send().await`: awaiting a link's queue
                    // here blocks the ONE message loop, and with it every later
                    // message on this socket — CLOSE_LINK and SHUTDOWN on every
                    // link. A freshly accepted link makes that unavoidable
                    // rather than unlikely, because its queue has no consumer at
                    // all until the dialler first writes (computenet-3gij). So a
                    // frame past the bound is refused with notice on its own
                    // link instead, and the loop stays free.
                    Some(frames) => match frames.try_send(msg.payload) {
                        Ok(()) => {}
                        Err(mpsc::error::TrySendError::Full(_)) => {
                            send(
                                &out,
                                Message::new(
                                    kind::ERROR,
                                    msg.link,
                                    format!(
                                        "link {}'s send queue is full ({QUEUE_DEPTH} frames outstanding); the frame was not sent",
                                        msg.link
                                    )
                                    .into_bytes(),
                                ),
                            )
                            .await;
                        }
                        Err(mpsc::error::TrySendError::Closed(_)) => {
                            send(
                                &out,
                                Message::new(
                                    kind::ERROR,
                                    msg.link,
                                    format!("link {} is no longer sending", msg.link).into_bytes(),
                                ),
                            )
                            .await;
                        }
                    },
                    None => {
                        send(
                            &out,
                            Message::new(
                                kind::ERROR,
                                msg.link,
                                format!("no such link: {}", msg.link).into_bytes(),
                            ),
                        )
                        .await;
                    }
                }
            }
            kind::CLOSE_LINK => {
                let watcher = links
                    .lock()
                    .expect("links mutex")
                    .get(&msg.link)
                    .map(|h| h.watcher.clone());
                match watcher {
                    // LINK_DOWN is not emitted here: the link's own observer task
                    // reports it, so a host close and a peer close produce the
                    // same single notification.
                    Some(watcher) => watcher.close(),
                    None => {
                        send(
                            &out,
                            Message::new(
                                kind::ERROR,
                                msg.link,
                                format!("no such link: {}", msg.link).into_bytes(),
                            ),
                        )
                        .await;
                    }
                }
            }
            kind::WATCH_PEERS => {
                if msg.link != CONTROL_LINK {
                    send(
                        &out,
                        Message::new(
                            kind::ERROR,
                            msg.link,
                            format!("WATCH_PEERS is a control message; got link {}", msg.link)
                                .into_bytes(),
                        ),
                    )
                    .await;
                } else if !msg.payload.is_empty() {
                    send(
                        &out,
                        Message::control(
                            kind::ERROR,
                            format!(
                                "WATCH_PEERS takes no payload; got {} bytes",
                                msg.payload.len()
                            )
                            .into_bytes(),
                        ),
                    )
                    .await;
                } else {
                    // Idempotent, and deliberately silent about whether
                    // anything is enumerating: a sidecar with no mDNS lookup
                    // answers WATCHING too and then never emits, so the host
                    // learns that from the absence of events rather than from
                    // an error (`PROTOCOL.md` §3, WATCH_PEERS).
                    if watching.is_none() {
                        if let Some(mdns) = endpoint.mdns().cloned() {
                            watching = Some(spawn_peer_watch(mdns, endpoint.clone(), out.clone()));
                        }
                    }
                    send(&out, Message::control(kind::WATCHING, Vec::new())).await;
                }
            }
            kind::SHUTDOWN => break ServeOutcome::Shutdown,
            other => {
                send(
                    &out,
                    Message::new(
                        kind::ERROR,
                        msg.link,
                        format!("unknown message kind 0x{other:02x}").into_bytes(),
                    ),
                )
                .await;
            }
        }
    };

    for handle in links.lock().expect("links mutex").values() {
        handle.watcher.close();
    }
    if let Some(accepting) = accepting {
        accepting.abort();
    }
    // Discovery events are scoped to this host connection (`PROTOCOL.md` §3):
    // they stop here, and a reconnecting host subscribes afresh.
    if let Some(watching) = watching {
        watching.abort();
    }
    drop(out);
    let _ = writer_task.await;
    Ok(outcome)
}

async fn send(out: &mpsc::Sender<Message>, msg: Message) {
    let _ = out.send(msg).await;
}

/// `ADD_PEER` payload: 32-byte endpoint id, then a UTF-8 comma-separated list of
/// socket addresses. An empty list is legal and adds no transport addresses.
fn parse_peer(payload: &[u8]) -> Option<EndpointAddr> {
    let id = endpoint_id_from_slice(payload)?;
    let text = std::str::from_utf8(&payload[32..]).ok()?;
    let mut addrs = Vec::new();
    for part in text.split(',').filter(|p| !p.trim().is_empty()) {
        addrs.push(TransportAddr::Ip(part.trim().parse::<SocketAddr>().ok()?));
    }
    Some(EndpointAddr::from_parts(id, addrs))
}

/// Subscribes to the mDNS lookup and forwards its events to the host for as
/// long as this task lives.
///
/// Split from [`forward_discovery`] at exactly the `subscribe()` await so the
/// forwarding rules can be pinned by a unit test over an injected stream,
/// without any multicast — which is the only way the `Expired` half is
/// affordable to test at all (the real expiry is 30–43 s, `ne2oh-B5`).
fn spawn_peer_watch(
    mdns: MdnsAddressLookup,
    endpoint: SidecarEndpoint,
    out: mpsc::Sender<Message>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let self_id = endpoint.id();
        let events = mdns.subscribe().await;
        forward_discovery(self_id, events, endpoint, out).await;
    })
}

/// Forwards discovery events to the host, one message each, and nothing else.
///
/// Doing nothing else is a requirement rather than a simplification: the
/// subscription is a 20-slot channel filled with `try_send`, so a subscriber
/// that pauses **loses events** (`ne2oh-B4`). Everything expensive — dial
/// policy, dedup, backoff — belongs above this, in the host.
///
/// Returns when the stream ends.
async fn forward_discovery(
    self_id: iroh::EndpointId,
    mut events: impl Stream<Item = DiscoveryEvent> + Unpin,
    endpoint: SidecarEndpoint,
    out: mpsc::Sender<Message>,
) {
    while let Some(event) = events.next().await {
        match event {
            DiscoveryEvent::Discovered { endpoint_info, .. } => {
                // The crate already drops our own id before it reaches a
                // subscriber; this is the second layer F1-D5 asks for, so the
                // rule holds even against a lookup that does not.
                if endpoint_info.endpoint_id == self_id {
                    continue;
                }
                let addr = endpoint_info.into_endpoint_addr();
                let payload = peer_discovered_payload(&addr);
                // add_peer BEFORE the send, never after: the host is entitled
                // to DIAL the moment it reads PEER_DISCOVERED, with no
                // ADD_PEER in between (`PROTOCOL.md` §3), and that only holds
                // if the address is already in the lookup when the message
                // leaves.
                endpoint.add_peer(addr);
                send(&out, Message::control(kind::PEER_DISCOVERED, payload)).await;
            }
            DiscoveryEvent::Expired { endpoint_id } => {
                send(
                    &out,
                    Message::control(kind::PEER_EXPIRED, endpoint_id.as_bytes().to_vec()),
                )
                .await;
            }
            // `DiscoveryEvent` is `#[non_exhaustive]`: a variant this crate
            // does not know about is not a protocol event, so it is ignored
            // rather than guessed at.
            _ => {}
        }
    }
}

fn spawn_accept_loop(
    endpoint: SidecarEndpoint,
    out: mpsc::Sender<Message>,
    links: Links,
    next_inbound: Arc<AtomicU64>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        loop {
            match endpoint.accept_pending().await {
                Ok(Some(pending)) => {
                    // Inbound links take EVEN ids; host-dialled links take odd
                    // ones. That split is what lets the host name a link in DIAL
                    // before the sidecar has one, without the two ever colliding.
                    let id = 2 * next_inbound.fetch_add(1, Ordering::Relaxed);
                    register_inbound(id, pending, out.clone(), links.clone()).await;
                }
                Ok(None) => break,
                Err(e) => {
                    send(
                        &out,
                        Message::control(kind::ERROR, format!("accept failed: {e}").into_bytes()),
                    )
                    .await;
                }
            }
        }
    })
}

/// Announces a link and registers it, before its stream exists.
///
/// Order matters and is part of the contract: `LINK_UP` is queued before any
/// pump starts, so the host never sees `DATA` or `LINK_DOWN` for a link it has
/// not been told about. The returned receiver is the link's send queue — host
/// `DATA` may already be queued on it while the stream is still being adopted.
async fn announce_link(
    id: u64,
    remote: iroh::EndpointId,
    direction: u8,
    watcher: LinkWatcher,
    out: &mpsc::Sender<Message>,
    links: &Links,
) -> mpsc::Receiver<Vec<u8>> {
    let (frames, frames_rx) = mpsc::channel::<Vec<u8>>(QUEUE_DEPTH);
    links
        .lock()
        .expect("links mutex")
        .insert(id, LinkHandle { frames, watcher });

    let mut up = remote.as_bytes().to_vec();
    up.push(direction);
    send(out, Message::new(kind::LINK_UP, id, up)).await;
    frames_rx
}

/// Reports an inbound link up as soon as the peer connects, and adopts its
/// stream in the background.
///
/// The stream only becomes visible when the dialler writes its first frame (see
/// [`PendingLink`]), so waiting for it here would leave the host blind to a peer
/// that has connected but not yet spoken.
async fn register_inbound(id: u64, pending: PendingLink, out: mpsc::Sender<Message>, links: Links) {
    let watcher = pending.watcher();
    let remote = pending.remote();
    let frames_rx =
        announce_link(id, remote, DIRECTION_INBOUND, watcher.clone(), &out, &links).await;

    tokio::spawn(async move {
        let established = tokio::select! {
            link = pending.establish() => link,
            down = watcher.closed() => {
                links.lock().expect("links mutex").remove(&id);
                send(&out, Message::new(kind::LINK_DOWN, id, down.reason.into_bytes())).await;
                return;
            }
        };
        match established {
            Ok(link) => start_pumps(id, link, watcher, frames_rx, out, links).await,
            Err(e) => {
                links.lock().expect("links mutex").remove(&id);
                send(
                    &out,
                    Message::new(
                        kind::LINK_DOWN,
                        id,
                        format!("the link's stream was never established: {e}").into_bytes(),
                    ),
                )
                .await;
            }
        }
    });
}

/// Starts a link's two pumps: host → peer, and peer → host with the link-down
/// observation folded in.
async fn start_pumps(
    id: u64,
    link: Link,
    watcher: LinkWatcher,
    mut frames_rx: mpsc::Receiver<Vec<u8>>,
    out: mpsc::Sender<Message>,
    links: Links,
) {
    let (mut sender, mut receiver) = link.into_split();

    // Host → peer.
    let send_errors = out.clone();
    tokio::spawn(async move {
        while let Some(payload) = frames_rx.recv().await {
            if let Err(e) = sender.send_frame(&payload).await {
                send(
                    &send_errors,
                    Message::new(kind::ERROR, id, format!("send failed: {e}").into_bytes()),
                )
                .await;
                break;
            }
        }
    });

    // Peer → host, plus the link-down observation. Whichever ends first wins:
    // the stream ending means no further frame can arrive on this link, and the
    // connection closing means the same; either way exactly one LINK_DOWN is
    // emitted and the link is deregistered.
    tokio::spawn(async move {
        let reason = tokio::select! {
            reason = pump_frames(&mut receiver, &out, id) => reason,
            down = watcher.closed() => down.reason,
        };
        links.lock().expect("links mutex").remove(&id);
        send(&out, Message::new(kind::LINK_DOWN, id, reason.into_bytes())).await;
    });
}

async fn pump_frames(
    receiver: &mut crate::link::LinkReceiver,
    out: &mpsc::Sender<Message>,
    id: u64,
) -> String {
    loop {
        match receiver.recv_frame().await {
            Ok(Some(payload)) => send(out, Message::new(kind::DATA, id, payload)).await,
            Ok(None) => return "peer finished the link's stream".to_string(),
            Err(e) => return format!("link read failed: {e}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use iroh::address_lookup::{EndpointData, EndpointInfo};

    use super::*;
    use crate::endpoint::SidecarConfig;

    /// The `PEER_DISCOVERED` payload really is an `ADD_PEER` payload — pinned
    /// by parsing it back with `ADD_PEER`'s own parser rather than by
    /// re-describing the shape — and its address order is `EndpointAddr`'s
    /// `BTreeSet` order, which puts the v4 socket before the v6 one.
    #[test]
    fn a_peer_discovered_payload_is_an_add_peer_payload() {
        let id = iroh::SecretKey::generate().public();
        // Deliberately offered v6-first, to show the payload's order is the
        // set's and not the caller's.
        let addr = EndpointAddr::from_parts(
            id,
            [
                TransportAddr::Ip("[::1]:41001".parse().expect("literal v6 socket")),
                TransportAddr::Ip("127.0.0.1:41001".parse().expect("literal v4 socket")),
            ],
        );

        let payload = peer_discovered_payload(&addr);

        assert_eq!(&payload[..32], id.as_bytes(), "32-byte endpoint id first");
        assert_eq!(
            &payload[32..],
            b"127.0.0.1:41001,[::1]:41001",
            "comma-separated IP sockets, in EndpointAddr's BTreeSet order"
        );
        assert_eq!(
            parse_peer(&payload),
            Some(addr),
            "ADD_PEER's parser accepts it and recovers the same address"
        );
    }

    /// An endpoint with no addresses yields an empty list, which `PROTOCOL.md`
    /// says is legal on both sides.
    #[test]
    fn a_peer_with_no_ip_addresses_yields_an_empty_list() {
        let id = iroh::SecretKey::generate().public();
        let payload = peer_discovered_payload(&EndpointAddr::from_parts(id, []));

        assert_eq!(payload.len(), 32);
        assert_eq!(parse_peer(&payload).map(|a| a.id), Some(id));
    }

    /// The whole of `forward_discovery`'s contract, network-free: an injected
    /// event stream stands in for the mDNS lookup, so the `Expired` mapping is
    /// pinned without the lookup's real 30–43 s expiry (`ne2oh-B5`) and the
    /// self-filter without a second host on the LAN.
    ///
    /// The strongest form of the add_peer-before-send rule is asserted here:
    /// the dial of the discovered id **fails before** the events are forwarded
    /// and **succeeds after**, with no `ADD_PEER` anywhere.
    #[tokio::test]
    async fn forwarding_feeds_the_lookup_before_it_announces_the_peer() {
        let us = SidecarEndpoint::bind(SidecarConfig::offline_loopback())
            .await
            .expect("bind the watching endpoint");
        let peer = SidecarEndpoint::bind(SidecarConfig::offline_loopback())
            .await
            .expect("bind the discovered endpoint");

        // A live acceptor, so a successful dial is a real connection rather
        // than a differently-shaped failure. The pending links are held, not
        // dropped, so the connections they carry stay open.
        let acceptor = peer.clone();
        let accept_task = tokio::spawn(async move {
            let mut held = Vec::new();
            while let Ok(Some(pending)) = acceptor.accept_pending().await {
                held.push(pending);
            }
        });

        // Before: `us` has been told nothing about `peer`, so the dial cannot
        // even be attempted.
        let before_err = match us.dial(peer.id()).await {
            Err(e) => e,
            Ok(_) => panic!("dialling an endpoint with no addressing information must fail"),
        };

        // A one-slot writer queue, pre-filled, so the forwarding task PARKS on
        // its `PEER_DISCOVERED` send. That is what makes the ordering
        // observable rather than merely coded: while it is parked, the send
        // has not happened yet, so a dial that succeeds at that moment proves
        // add_peer ran first. With the two swapped, the dial below never
        // succeeds and this test fails on its deadline.
        let (out, mut out_rx) = mpsc::channel::<Message>(1);
        out.send(Message::control(kind::ERROR, b"filler".to_vec()))
            .await
            .expect("the receiver is alive");
        let peer_addr = peer.bound_addr();
        let events = futures_util::stream::iter([
            // Our own id: the crate drops this already, and so do we.
            DiscoveryEvent::Discovered {
                endpoint_info: EndpointInfo::from_parts(us.id(), EndpointData::new(Vec::new())),
                last_updated: None,
            },
            DiscoveryEvent::Discovered {
                endpoint_info: EndpointInfo::from_parts(
                    peer.id(),
                    EndpointData::new(peer_addr.addrs.iter().cloned().collect()),
                ),
                last_updated: None,
            },
            DiscoveryEvent::Expired {
                endpoint_id: peer.id(),
            },
        ]);

        let forwarding = {
            let us = us.clone();
            tokio::spawn(async move { forward_discovery(us.id(), events, us, out).await })
        };

        // While the task is parked on its send: the dial already resolves.
        let mut link = None;
        let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        while tokio::time::Instant::now() < deadline {
            if let Ok(established) = us.dial(peer.id()).await {
                link = Some(established);
                break;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        assert!(
            link.is_some(),
            "a bare dial must resolve from the forwarded addresses before PEER_DISCOVERED is \
             sent — add_peer precedes the send. Before forwarding, the same dial failed with: \
             {before_err}"
        );

        // Draining unparks the task, which then finishes the stream and drops
        // its sender.
        let filler = out_rx.recv().await.expect("the filler comes out first");
        assert_eq!(filler.payload, b"filler".to_vec());

        let discovered = out_rx.recv().await.expect("PEER_DISCOVERED was emitted");
        assert_eq!(
            (discovered.kind, discovered.link),
            (kind::PEER_DISCOVERED, CONTROL_LINK)
        );
        assert_eq!(
            discovered.payload,
            peer_discovered_payload(&peer_addr),
            "the peer's own id and bound sockets, in ADD_PEER's shape"
        );

        let expired = out_rx.recv().await.expect("PEER_EXPIRED was emitted");
        assert_eq!(
            (expired.kind, expired.link),
            (kind::PEER_EXPIRED, CONTROL_LINK)
        );
        assert_eq!(expired.payload, peer.id().as_bytes().to_vec());

        // Exactly those two messages: the Discovered naming our own id
        // produced nothing at all. The sender was moved into
        // `forward_discovery` and dropped when it returned, so the channel is
        // closed and this cannot pass by racing a third message.
        assert!(
            out_rx.recv().await.is_none(),
            "a Discovered naming our own id emits nothing"
        );
        forwarding
            .await
            .expect("the forwarding task ran to the end of the stream");

        drop(link);
        accept_task.abort();
        us.close().await;
        peer.close().await;
    }
}
