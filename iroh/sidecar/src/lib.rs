//! ComputeNet iroh sidecar — library half.
//!
//! A [`SidecarEndpoint`] binds an iroh endpoint from a generated or supplied
//! ed25519 secret key. It can accept inbound connections and dial a peer **by
//! endpoint id** (iroh 1.0's name for what earlier releases, and ComputeNet's
//! own specs, call a NodeId — the ed25519 public key). Each peer link owns
//! exactly one bi-directional QUIC stream, carrying frames with a 4-byte
//! big-endian length prefix in both directions.
//!
//! ```no_run
//! use computenet_iroh_sidecar::{SidecarConfig, SidecarEndpoint};
//!
//! # async fn run() -> computenet_iroh_sidecar::Result<()> {
//! let listener = SidecarEndpoint::bind(SidecarConfig::offline_loopback()).await?;
//! let dialler = SidecarEndpoint::bind(SidecarConfig::offline_loopback()).await?;
//!
//! // Offline mode resolves nothing by itself, so hand over the address once…
//! dialler.add_peer(listener.bound_addr());
//! // …and from here on the peer is addressed by its id alone.
//! let mut link = dialler.dial(listener.id()).await?;
//! link.send_frame(b"hi").await?;
//! # Ok(())
//! # }
//! ```
//!
//! # The two protocols
//!
//! Peer-to-peer traffic rides QUIC ([`frame`]): one bi-directional stream per
//! link, 4-byte big-endian length prefix per frame.
//!
//! The host process talks to the sidecar over a **loopback TCP** connection
//! instead ([`protocol`], [`serve`]), whose messages carry the same 4-byte
//! length prefix plus a kind byte and an 8-byte link id. That header multiplexes
//! control traffic and every link's frames onto the one host connection — a
//! *local* multiplexing that never becomes a second QUIC stream. `PROTOCOL.md`
//! beside this crate's `Cargo.toml` is its normative description.
//!
//! The binary is a thin wrapper: bind an endpoint, bind an ephemeral
//! `127.0.0.1` TCP port, print one JSON handshake line
//! ([`handshake_line`]), and hand each host connection to [`serve`].

#![deny(missing_docs)]

mod endpoint;
mod error;
pub mod frame;
mod link;
pub mod protocol;
mod server;

pub use endpoint::{LookupMode, SidecarConfig, SidecarEndpoint, ALPN};
pub use error::{Error, Result};
pub use frame::{LENGTH_PREFIX_LEN, MAX_FRAME_LEN};
pub use link::{Link, LinkDown, LinkId, LinkReceiver, LinkSender, LinkWatcher, PendingLink};
pub use protocol::{handshake_line, Message, MAX_MESSAGE_LEN, MSG_HEADER_LEN};
pub use server::{serve, serve_io, ServeOutcome};

// Re-exported so callers need not depend on iroh directly for the few types
// that appear in this crate's signatures.
pub use iroh::{EndpointAddr, EndpointId, SecretKey};
