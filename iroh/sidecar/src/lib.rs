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
//! Scope: this is the library only. The bin that wraps it in a local-socket
//! protocol toward the host JVM is a separate work item; the API here is shaped
//! for it — links carry ids, frames are sent and received one at a time, and
//! link-down is observable via [`Link::closed`].

#![deny(missing_docs)]

mod endpoint;
mod error;
pub mod frame;
mod link;

pub use endpoint::{LookupMode, SidecarConfig, SidecarEndpoint, ALPN};
pub use error::{Error, Result};
pub use frame::{LENGTH_PREFIX_LEN, MAX_FRAME_LEN};
pub use link::{Link, LinkDown, LinkId, LinkReceiver, LinkSender};

// Re-exported so callers need not depend on iroh directly for the few types
// that appear in this crate's signatures.
pub use iroh::{EndpointAddr, EndpointId, SecretKey};
