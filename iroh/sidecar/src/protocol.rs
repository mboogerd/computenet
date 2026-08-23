//! The local-socket protocol between the sidecar and its host process.
//!
//! The normative description — every message kind, every field, every byte
//! offset — is [`PROTOCOL.md`](../PROTOCOL.md) next to this crate's
//! `Cargo.toml`. This module is its executable half; the two are meant to be
//! read together, and a kind added here without a `PROTOCOL.md` entry is a
//! defect.
//!
//! # Message layout
//!
//! ```text
//! +--------+--------+--------+--------+--------+---8 bytes---+--- body ---+
//! |        length (u32, big-endian)   |  kind  |   link id   |  payload   |
//! +--------+--------+--------+--------+--------+-------------+------------+
//! ```
//!
//! `length` counts the bytes that follow it: `1 + 8 + payload.len()`, so the
//! smallest legal message is 9 bytes long and carries no payload. The kind byte
//! and the 8-byte big-endian link id are what multiplex control traffic and any
//! number of per-link frame streams onto the **one** host TCP connection.
//!
//! That multiplexing is local only. It never becomes a second QUIC stream: a
//! peer link still owns exactly one bi-directional QUIC stream (see
//! [`crate::link`]), and a [`kind::DATA`] message's payload is exactly the frame
//! that rides it.

use std::io;

use iroh::EndpointId;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::frame::{LENGTH_PREFIX_LEN, MAX_FRAME_LEN};

/// Bytes of header after the length prefix: kind (1) + link id (8).
pub const MSG_HEADER_LEN: usize = 9;

/// Largest message body (header + payload) this crate will send or accept.
///
/// Sized so a [`kind::DATA`] payload may be a maximum-size peer frame and
/// nothing larger, which keeps the socket's allocation bound tied to the QUIC
/// side's rather than being an independent number to keep in step.
pub const MAX_MESSAGE_LEN: usize = MSG_HEADER_LEN + MAX_FRAME_LEN;

/// The link id reserved for messages that are not about a particular link.
pub const CONTROL_LINK: u64 = 0;

/// Message kinds. See `PROTOCOL.md` for each one's payload and its reply.
///
/// Kinds below `0x80` originate at the host; kinds at or above `0x80` originate
/// at the sidecar. [`kind::DATA`] is the single exception and travels both ways.
pub mod kind {
    /// Host → sidecar. Ask for this endpoint's id. Reply: [`ID`].
    pub const GET_ID: u8 = 0x01;
    /// Host → sidecar. Start accepting inbound links. Reply: [`LISTENING`].
    pub const LISTEN: u8 = 0x02;
    /// Host → sidecar. Teach the endpoint how to reach a peer. Reply:
    /// [`PEER_ADDED`].
    pub const ADD_PEER: u8 = 0x03;
    /// Host → sidecar. Dial a peer by id on a host-chosen odd link id. Reply:
    /// [`LINK_UP`] or [`ERROR`], both on that link id.
    pub const DIAL: u8 = 0x04;
    /// Both directions. One peer frame on one link; the payload is the frame.
    pub const DATA: u8 = 0x05;
    /// Host → sidecar. Close one link. Reply: [`LINK_DOWN`].
    pub const CLOSE_LINK: u8 = 0x06;
    /// Host → sidecar. Close every link and end the process.
    pub const SHUTDOWN: u8 = 0x07;

    /// Sidecar → host. Payload: the 32-byte endpoint id.
    pub const ID: u8 = 0x81;
    /// Sidecar → host. Payload: UTF-8 comma-separated bound socket addresses.
    pub const LISTENING: u8 = 0x82;
    /// Sidecar → host. Payload: the 32-byte peer id that was added.
    pub const PEER_ADDED: u8 = 0x83;
    /// Sidecar → host. Payload: 32-byte remote id + 1 direction byte.
    pub const LINK_UP: u8 = 0x84;
    /// Sidecar → host. Payload: UTF-8 reason. Terminal for that link id.
    pub const LINK_DOWN: u8 = 0x85;
    /// Sidecar → host. Payload: UTF-8 message. Link id names the link it
    /// concerns, or [`super::CONTROL_LINK`].
    pub const ERROR: u8 = 0x86;
}

/// [`kind::LINK_UP`] direction byte: this side dialled.
pub const DIRECTION_OUTBOUND: u8 = 0x00;
/// [`kind::LINK_UP`] direction byte: this side accepted.
pub const DIRECTION_INBOUND: u8 = 0x01;

/// One protocol message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Message {
    /// See [`kind`].
    pub kind: u8,
    /// The link this message concerns, or [`CONTROL_LINK`].
    pub link: u64,
    /// Kind-specific body.
    pub payload: Vec<u8>,
}

impl Message {
    /// A message about one link.
    pub fn new(kind: u8, link: u64, payload: Vec<u8>) -> Self {
        Message {
            kind,
            link,
            payload,
        }
    }

    /// A message on [`CONTROL_LINK`].
    pub fn control(kind: u8, payload: Vec<u8>) -> Self {
        Message::new(kind, CONTROL_LINK, payload)
    }

    /// The message as it appears on the socket, length prefix included.
    pub fn encode(&self) -> Vec<u8> {
        let body_len = MSG_HEADER_LEN + self.payload.len();
        let mut out = Vec::with_capacity(LENGTH_PREFIX_LEN + body_len);
        out.extend_from_slice(&(body_len as u32).to_be_bytes());
        out.push(self.kind);
        out.extend_from_slice(&self.link.to_be_bytes());
        out.extend_from_slice(&self.payload);
        out
    }
}

/// Writes one message.
pub async fn write_message<W: AsyncWrite + Unpin>(w: &mut W, msg: &Message) -> io::Result<()> {
    if MSG_HEADER_LEN + msg.payload.len() > MAX_MESSAGE_LEN {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!(
                "message body of {} bytes exceeds the {MAX_MESSAGE_LEN}-byte limit",
                MSG_HEADER_LEN + msg.payload.len()
            ),
        ));
    }
    w.write_all(&msg.encode()).await?;
    w.flush().await
}

/// Reads one message. `Ok(None)` on a clean end of the socket at a message
/// boundary; an end *inside* a message is an error, so a half-written message is
/// never mistaken for an orderly disconnect.
pub async fn read_message<R: AsyncRead + Unpin>(r: &mut R) -> io::Result<Option<Message>> {
    let mut prefix = [0u8; LENGTH_PREFIX_LEN];
    match r.read_exact(&mut prefix).await {
        Ok(_) => {}
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let body_len = u32::from_be_bytes(prefix) as usize;
    if body_len < MSG_HEADER_LEN {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!(
                "message body of {body_len} bytes is shorter than the {MSG_HEADER_LEN}-byte header"
            ),
        ));
    }
    if body_len > MAX_MESSAGE_LEN {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("peer announced a {body_len}-byte message; the limit is {MAX_MESSAGE_LEN}"),
        ));
    }
    let mut body = vec![0u8; body_len];
    r.read_exact(&mut body).await?;
    let kind = body[0];
    let mut link_bytes = [0u8; 8];
    link_bytes.copy_from_slice(&body[1..MSG_HEADER_LEN]);
    Ok(Some(Message {
        kind,
        link: u64::from_be_bytes(link_bytes),
        payload: body[MSG_HEADER_LEN..].to_vec(),
    }))
}

/// The single JSON handshake line the binary writes to stdout once bound:
/// `{"port":<u16>,"nodeId":"<64 lowercase hex chars>"}` plus a newline.
///
/// `nodeId` is the ed25519 public key — iroh 1.0 calls it an `EndpointId`, the
/// ComputeNet specs call it a NodeId — as 32 bytes in lowercase hex, the same
/// encoding the protocol's binary id fields carry.
pub fn handshake_line(port: u16, id: EndpointId) -> String {
    format!(
        "{{\"port\":{port},\"nodeId\":\"{}\"}}",
        encode_hex(id.as_bytes())
    )
}

/// Lowercase hex, as used by the handshake line.
pub fn encode_hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(char::from_digit((b >> 4) as u32, 16).expect("nibble is a hex digit"));
        s.push(char::from_digit((b & 0x0f) as u32, 16).expect("nibble is a hex digit"));
    }
    s
}

/// Parses lowercase or uppercase hex; `None` on an odd length or a non-hex
/// character.
pub fn decode_hex(s: &str) -> Option<Vec<u8>> {
    if !s.len().is_multiple_of(2) {
        return None;
    }
    let chars: Vec<char> = s.chars().collect();
    chars
        .chunks(2)
        .map(|pair| {
            let hi = pair[0].to_digit(16)?;
            let lo = pair[1].to_digit(16)?;
            Some(((hi << 4) | lo) as u8)
        })
        .collect()
}

/// Reads a 32-byte endpoint id from the head of a payload.
pub fn endpoint_id_from_slice(bytes: &[u8]) -> Option<EndpointId> {
    let raw: [u8; 32] = bytes.get(..32)?.try_into().ok()?;
    EndpointId::from_bytes(&raw).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_message_round_trips_through_its_own_encoding() {
        let msg = Message::new(kind::DATA, 7, vec![1, 2, 3]);
        let encoded = msg.encode();
        assert_eq!(
            &encoded[..4],
            &(12u32).to_be_bytes(),
            "length counts kind + link id + payload"
        );
        assert_eq!(encoded[4], kind::DATA);
        assert_eq!(&encoded[5..13], &7u64.to_be_bytes());
        assert_eq!(&encoded[13..], &[1, 2, 3]);
    }

    #[tokio::test]
    async fn reading_recovers_message_boundaries_from_a_concatenated_stream() {
        let a = Message::control(kind::GET_ID, Vec::new());
        let b = Message::new(kind::DATA, 3, vec![9; 70_000]);
        let mut wire = a.encode();
        wire.extend_from_slice(&b.encode());

        let mut cursor = wire.as_slice();
        assert_eq!(read_message(&mut cursor).await.unwrap(), Some(a));
        assert_eq!(read_message(&mut cursor).await.unwrap(), Some(b));
        assert_eq!(read_message(&mut cursor).await.unwrap(), None);
    }

    #[test]
    fn hex_round_trips() {
        let bytes = [0x00, 0x0f, 0xa5, 0xff];
        assert_eq!(encode_hex(&bytes), "000fa5ff");
        assert_eq!(decode_hex("000fa5ff"), Some(bytes.to_vec()));
        assert_eq!(decode_hex("abc"), None);
        assert_eq!(decode_hex("zz"), None);
    }
}
