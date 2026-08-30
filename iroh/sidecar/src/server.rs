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

use iroh::{EndpointAddr, TransportAddr};
use tokio::{
    io::{AsyncRead, AsyncWrite},
    net::TcpStream,
    sync::mpsc,
};

use crate::{
    endpoint::SidecarEndpoint,
    link::{Link, LinkWatcher, PendingLink},
    protocol::{
        endpoint_id_from_slice, kind, read_message, write_message, Message, CONTROL_LINK,
        DIRECTION_INBOUND, DIRECTION_OUTBOUND,
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
