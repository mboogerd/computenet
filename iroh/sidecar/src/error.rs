//! Errors produced by the sidecar library.

use std::{error::Error as StdError, fmt};

/// Result alias for this crate.
pub type Result<T> = std::result::Result<T, Error>;

/// What went wrong.
///
/// Foreign errors (iroh / QUIC) are kept as a boxed source rather than being
/// re-modelled: their variants are iroh's to change, and the sidecar only needs
/// to distinguish *where* a failure happened from *whether the frame stream is
/// still intact*.
#[derive(Debug)]
pub enum Error {
    /// Binding the local endpoint failed.
    Bind(Box<dyn StdError + Send + Sync>),
    /// Dialling a peer failed.
    Dial(Box<dyn StdError + Send + Sync>),
    /// Accepting an inbound connection failed.
    Accept(Box<dyn StdError + Send + Sync>),
    /// Opening or accepting the link's single bi-directional stream failed.
    OpenStream(Box<dyn StdError + Send + Sync>),
    /// Writing to the link's stream failed.
    Send(Box<dyn StdError + Send + Sync>),
    /// Reading from the link's stream failed.
    Recv(Box<dyn StdError + Send + Sync>),
    /// The peer's length prefix announced a frame beyond [`crate::MAX_FRAME_LEN`].
    ///
    /// Never a partial read: the frame is refused on its prefix, before its body
    /// is buffered.
    FrameTooLarge {
        /// Length the peer announced.
        len: usize,
        /// Largest length this endpoint accepts.
        max: usize,
    },
    /// The peer ended the stream part-way through a frame — a prefix without its
    /// body, or a body shorter than its prefix. Distinct from a clean end of
    /// stream, which is reported as `Ok(None)` by
    /// [`LinkReceiver::recv_frame`](crate::LinkReceiver::recv_frame).
    TruncatedFrame {
        /// Bytes read before the stream ended.
        read: usize,
        /// Bytes the frame still needed.
        expected: usize,
    },
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Bind(e) => write!(f, "binding the iroh endpoint failed: {e}"),
            Error::Dial(e) => write!(f, "dialling the peer failed: {e}"),
            Error::Accept(e) => write!(f, "accepting an inbound connection failed: {e}"),
            Error::OpenStream(e) => write!(f, "establishing the link's stream failed: {e}"),
            Error::Send(e) => write!(f, "sending a frame failed: {e}"),
            Error::Recv(e) => write!(f, "receiving a frame failed: {e}"),
            Error::FrameTooLarge { len, max } => {
                write!(f, "peer announced a {len}-byte frame; the limit is {max}")
            }
            Error::TruncatedFrame { read, expected } => write!(
                f,
                "stream ended mid-frame: {read} of {expected} bytes were read"
            ),
        }
    }
}

impl StdError for Error {
    fn source(&self) -> Option<&(dyn StdError + 'static)> {
        match self {
            Error::Bind(e)
            | Error::Dial(e)
            | Error::Accept(e)
            | Error::OpenStream(e)
            | Error::Send(e)
            | Error::Recv(e) => Some(&**e),
            Error::FrameTooLarge { .. } | Error::TruncatedFrame { .. } => None,
        }
    }
}
