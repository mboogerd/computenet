//! The wire framing carried on a peer link's QUIC stream.
//!
//! # Frame format
//!
//! ```text
//! +--------+--------+--------+--------+----------------------------+
//! |          length (u32, big-endian)  |  payload (`length` bytes)  |
//! +--------+--------+--------+--------+----------------------------+
//! ```
//!
//! A QUIC stream is a byte stream, not a message stream: a peer's `write_all` of
//! 70000 bytes arrives as however many chunks the network produced. The 4-byte
//! big-endian prefix is what restores message boundaries, so a 3-byte frame
//! followed by a 70000-byte frame is read back as exactly those two frames, in
//! order, whatever the chunking underneath.
//!
//! Zero-length frames are legal on the wire and are delivered as empty payloads.
//! A frame whose prefix exceeds [`MAX_FRAME_LEN`] is refused before its body is
//! buffered, so a hostile or corrupt prefix cannot make the receiver allocate.

use iroh::endpoint::{ReadExactError, RecvStream, SendStream};

use crate::error::{Error, Result};

/// Bytes of length prefix ahead of every frame.
pub const LENGTH_PREFIX_LEN: usize = 4;

/// Largest frame this crate will send or accept, in bytes (16 MiB).
///
/// Chosen as a receive-side allocation bound, not a protocol constant: it is far
/// above the 70000-byte frame the link tests exercise and far below anything
/// that would let one prefix exhaust memory.
pub const MAX_FRAME_LEN: usize = 16 * 1024 * 1024;

/// Writes one length-prefixed frame.
///
/// The prefix and the payload go out as one `write_all` pair on the same stream;
/// nothing else may write to that stream concurrently, which is why a link owns
/// its [`SendStream`] exclusively.
pub(crate) async fn write_frame(stream: &mut SendStream, payload: &[u8]) -> Result<()> {
    if payload.len() > MAX_FRAME_LEN {
        return Err(Error::FrameTooLarge {
            len: payload.len(),
            max: MAX_FRAME_LEN,
        });
    }
    let prefix = (payload.len() as u32).to_be_bytes();
    stream
        .write_all(&prefix)
        .await
        .map_err(|e| Error::Send(Box::new(e)))?;
    stream
        .write_all(payload)
        .await
        .map_err(|e| Error::Send(Box::new(e)))?;
    Ok(())
}

/// Reads one length-prefixed frame.
///
/// Returns `Ok(None)` when the peer finished the stream cleanly on a frame
/// boundary. A stream that ends *inside* a frame is [`Error::TruncatedFrame`] —
/// the two are deliberately distinguishable, so a link going down mid-frame is
/// never mistaken for an orderly close.
pub(crate) async fn read_frame(stream: &mut RecvStream) -> Result<Option<Vec<u8>>> {
    let mut prefix = [0u8; LENGTH_PREFIX_LEN];
    match stream.read_exact(&mut prefix).await {
        Ok(()) => {}
        Err(ReadExactError::FinishedEarly(0)) => return Ok(None),
        Err(ReadExactError::FinishedEarly(read)) => {
            return Err(Error::TruncatedFrame {
                read,
                expected: LENGTH_PREFIX_LEN,
            })
        }
        Err(e) => return Err(Error::Recv(Box::new(e))),
    }

    let len = u32::from_be_bytes(prefix) as usize;
    if len > MAX_FRAME_LEN {
        return Err(Error::FrameTooLarge {
            len,
            max: MAX_FRAME_LEN,
        });
    }

    let mut payload = vec![0u8; len];
    match stream.read_exact(&mut payload).await {
        Ok(()) => Ok(Some(payload)),
        Err(ReadExactError::FinishedEarly(read)) => Err(Error::TruncatedFrame {
            read,
            expected: len,
        }),
        Err(e) => Err(Error::Recv(Box::new(e))),
    }
}
