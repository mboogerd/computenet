//! A peer link: exactly one bi-directional QUIC stream to one remote endpoint.
//!
//! # One stream per link
//!
//! A link owns a *single* bi-directional stream for its whole lifetime. Every
//! frame in both directions rides that stream. Opening a second stream for the
//! same link, or a stream per frame, is wrong even where it would work: ordering
//! is only guaranteed *within* a QUIC stream, so per-frame streams would let
//! frame 2 overtake frame 1, and this crate's ordering guarantee would quietly
//! become "usually".
//!
//! The dialling side calls `open_bi`, the accepting side `accept_bi`; the two
//! halves of that one stream are what [`LinkSender`] and [`LinkReceiver`] hold.

use std::fmt;

use iroh::{
    endpoint::{Connection, RecvStream, SendStream},
    EndpointId,
};

use crate::{
    error::Result,
    frame::{read_frame, write_frame},
};

/// Process-local identifier for a link.
///
/// Unique within one sidecar process for the lifetime of that process. It is not
/// derived from anything on the wire and means nothing to the peer: it exists so
/// a host process can name a link across the local-socket protocol.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct LinkId(u64);

impl LinkId {
    pub(crate) fn new(raw: u64) -> Self {
        LinkId(raw)
    }

    /// The underlying number, for encoding onto the local-socket protocol.
    pub fn as_u64(self) -> u64 {
        self.0
    }
}

impl fmt::Display for LinkId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "link-{}", self.0)
    }
}

/// Why a link went down.
///
/// Returned by [`Link::closed`] / [`LinkReceiver::closed`]; the "down" half of
/// the link up/down observation the host process programs against.
#[derive(Debug, Clone)]
pub struct LinkDown {
    /// The link that went down.
    pub link: LinkId,
    /// The peer it addressed.
    pub remote: EndpointId,
    /// Human-readable cause, as reported by the QUIC connection.
    pub reason: String,
}

/// An established link to one peer.
///
/// Holds the connection and both halves of its single bi-directional stream. Use
/// [`Link::into_split`] to send and receive concurrently.
#[derive(Debug)]
pub struct Link {
    sender: LinkSender,
    receiver: LinkReceiver,
}

impl Link {
    pub(crate) fn new(id: LinkId, conn: Connection, send: SendStream, recv: RecvStream) -> Self {
        let remote = conn.remote_id();
        Link {
            sender: LinkSender {
                id,
                remote,
                conn: conn.clone(),
                stream: send,
            },
            receiver: LinkReceiver {
                id,
                remote,
                conn,
                stream: recv,
            },
        }
    }

    /// This link's process-local id.
    pub fn id(&self) -> LinkId {
        self.sender.id
    }

    /// The peer on the other end.
    pub fn remote(&self) -> EndpointId {
        self.sender.remote
    }

    /// Sends one frame. See [`crate::frame`] for the format.
    pub async fn send_frame(&mut self, payload: &[u8]) -> Result<()> {
        self.sender.send_frame(payload).await
    }

    /// Receives one frame; `Ok(None)` once the peer closed the stream cleanly.
    pub async fn recv_frame(&mut self) -> Result<Option<Vec<u8>>> {
        self.receiver.recv_frame().await
    }

    /// Resolves when the link goes down, for whatever reason.
    pub async fn closed(&self) -> LinkDown {
        self.receiver.closed().await
    }

    /// A cloneable handle that observes and forces link-down independently of
    /// the halves that carry frames.
    ///
    /// [`Link::closed`] borrows the link, so a task that has moved the receiver
    /// into a read loop can no longer ask when the link went down. The watcher
    /// exists for exactly that: take one before splitting, and the read loop and
    /// the down-observer can run as separate tasks.
    pub fn watcher(&self) -> LinkWatcher {
        self.receiver.watcher()
    }

    /// Splits the link so one task can send while another receives — both still
    /// on the same single bi-directional stream.
    pub fn into_split(self) -> (LinkSender, LinkReceiver) {
        (self.sender, self.receiver)
    }

    /// Closes the link and its connection.
    pub fn close(self) {
        self.sender.close();
    }
}

/// The sending half of a link's single bi-directional stream.
#[derive(Debug)]
pub struct LinkSender {
    id: LinkId,
    remote: EndpointId,
    conn: Connection,
    stream: SendStream,
}

impl LinkSender {
    /// This link's process-local id.
    pub fn id(&self) -> LinkId {
        self.id
    }

    /// The peer on the other end.
    pub fn remote(&self) -> EndpointId {
        self.remote
    }

    /// Sends one frame.
    pub async fn send_frame(&mut self, payload: &[u8]) -> Result<()> {
        write_frame(&mut self.stream, payload).await
    }

    /// A cloneable link-down observer for this link. See [`Link::watcher`].
    pub fn watcher(&self) -> LinkWatcher {
        LinkWatcher {
            id: self.id,
            remote: self.remote,
            conn: self.conn.clone(),
        }
    }

    /// Finishes the stream, signalling a clean end of frames to the peer. The
    /// peer's next `recv_frame` yields `Ok(None)`.
    pub fn finish(&mut self) {
        // A stream that is already closed needs no finishing.
        let _ = self.stream.finish();
    }

    /// Closes the link and its connection.
    pub fn close(mut self) {
        self.finish();
        self.conn.close(0u32.into(), b"link closed");
    }
}

/// The receiving half of a link's single bi-directional stream.
#[derive(Debug)]
pub struct LinkReceiver {
    id: LinkId,
    remote: EndpointId,
    conn: Connection,
    stream: RecvStream,
}

impl LinkReceiver {
    /// This link's process-local id.
    pub fn id(&self) -> LinkId {
        self.id
    }

    /// The peer on the other end.
    pub fn remote(&self) -> EndpointId {
        self.remote
    }

    /// Receives one frame; `Ok(None)` once the peer closed the stream cleanly.
    pub async fn recv_frame(&mut self) -> Result<Option<Vec<u8>>> {
        read_frame(&mut self.stream).await
    }

    /// Resolves when the link goes down, for whatever reason.
    pub async fn closed(&self) -> LinkDown {
        self.watcher().closed().await
    }

    /// A cloneable link-down observer for this link. See [`Link::watcher`].
    pub fn watcher(&self) -> LinkWatcher {
        LinkWatcher {
            id: self.id,
            remote: self.remote,
            conn: self.conn.clone(),
        }
    }
}

/// A connection whose link exists but whose single bi-directional stream has not
/// been adopted yet.
///
/// QUIC reveals a stream to the peer only when its opener first writes, so an
/// accepting endpoint knows *who* connected long before `accept_bi` can return.
/// Splitting the two lets a sidecar report the link up immediately and adopt the
/// stream when the dialler's first frame arrives — the alternative being a link
/// that is invisible to its own host until the peer decides to talk.
#[derive(Debug)]
pub struct PendingLink {
    id: LinkId,
    conn: Connection,
}

impl PendingLink {
    pub(crate) fn new(id: LinkId, conn: Connection) -> Self {
        PendingLink { id, conn }
    }

    /// The id this link will have once established.
    pub fn id(&self) -> LinkId {
        self.id
    }

    /// The peer that connected. Known from the QUIC handshake, before any
    /// stream exists.
    pub fn remote(&self) -> EndpointId {
        self.conn.remote_id()
    }

    /// A link-down observer that is valid already — the connection can be lost,
    /// or closed locally, before its stream is ever adopted.
    pub fn watcher(&self) -> LinkWatcher {
        LinkWatcher {
            id: self.id,
            remote: self.conn.remote_id(),
            conn: self.conn.clone(),
        }
    }

    /// Adopts the link's one bi-directional stream, resolving when the dialler
    /// opens it by writing its first frame.
    pub async fn establish(self) -> Result<Link> {
        let (send, recv) = self
            .conn
            .accept_bi()
            .await
            .map_err(|e| crate::error::Error::OpenStream(Box::new(e)))?;
        Ok(Link::new(self.id, self.conn, send, recv))
    }
}

/// Observes — and can force — one link's transition to down, without holding
/// either half of its stream.
///
/// Cloneable and independent of [`LinkSender`] / [`LinkReceiver`], so the task
/// that pumps frames off a link and the task that reports the link going down
/// can be different tasks. This is the "report link up/down" half of the
/// contract the host process programs against.
#[derive(Debug, Clone)]
pub struct LinkWatcher {
    id: LinkId,
    remote: EndpointId,
    conn: Connection,
}

impl LinkWatcher {
    /// This link's process-local id.
    pub fn id(&self) -> LinkId {
        self.id
    }

    /// The peer on the other end.
    pub fn remote(&self) -> EndpointId {
        self.remote
    }

    /// Resolves when the link goes down, for whatever reason — the peer closing
    /// it, a local [`LinkWatcher::close`], or the connection being lost.
    pub async fn closed(&self) -> LinkDown {
        let reason = self.conn.closed().await.to_string();
        LinkDown {
            link: self.id,
            remote: self.remote,
            reason,
        }
    }

    /// Takes the link down locally. Every outstanding
    /// [`LinkWatcher::closed`] on this link then resolves.
    pub fn close(&self) {
        self.conn.close(0u32.into(), b"link closed");
    }
}
