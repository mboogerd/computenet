//! Two sidecar endpoints in one process: dial by endpoint id, and prove the
//! 4-byte length prefix restores frame boundaries on the link's single
//! bi-directional QUIC stream.

use std::time::Duration;

use computenet_iroh_sidecar::{SecretKey, SidecarConfig, SidecarEndpoint};

/// Deliberately awkward sizes: 3 bytes is smaller than the length prefix itself,
/// and 70000 bytes is past 64 KiB, so it cannot arrive in one QUIC chunk and a
/// receiver that ignored the prefix would see it split.
const SMALL: usize = 3;
const LARGE: usize = 70_000;

/// Bounds every await so a hang fails the test instead of wedging the run.
const TIMEOUT: Duration = Duration::from_secs(30);

fn payload(len: usize, seed: u8) -> Vec<u8> {
    (0..len)
        .map(|i| (i as u8).wrapping_mul(31).wrapping_add(seed))
        .collect()
}

async fn bind_pair() -> (SidecarEndpoint, SidecarEndpoint) {
    let listener = SidecarEndpoint::bind(SidecarConfig::offline_loopback())
        .await
        .expect("bind listener");
    let dialler = SidecarEndpoint::bind(SidecarConfig::offline_loopback())
        .await
        .expect("bind dialler");
    // The only address exchange in this test: from here on the peer is dialled
    // by its id alone.
    dialler.add_peer(listener.bound_addr());
    (listener, dialler)
}

#[tokio::test]
async fn frames_keep_their_boundaries_across_one_bidirectional_stream() {
    let (listener, dialler) = bind_pair().await;
    let listener_id = listener.id();

    let small = payload(SMALL, 7);
    let large = payload(LARGE, 11);
    let reply = payload(1234, 23);
    let (expected_small, expected_large, expected_reply) =
        (small.clone(), large.clone(), reply.clone());

    let accepted = tokio::spawn(async move {
        let mut link = tokio::time::timeout(TIMEOUT, listener.accept())
            .await
            .expect("accept did not time out")
            .expect("accept succeeded")
            .expect("endpoint still open");

        // Exactly two frames, in order, of exactly the lengths that were sent.
        let first = tokio::time::timeout(TIMEOUT, link.recv_frame())
            .await
            .expect("first frame did not time out")
            .expect("first frame read")
            .expect("stream not finished");
        let second = tokio::time::timeout(TIMEOUT, link.recv_frame())
            .await
            .expect("second frame did not time out")
            .expect("second frame read")
            .expect("stream not finished");

        // The reply travels back over the very same stream.
        link.send_frame(&expected_reply)
            .await
            .expect("reply sent on the same stream");

        // And after the peer finishes, the stream ends cleanly rather than
        // producing a third, spurious frame.
        let third = tokio::time::timeout(TIMEOUT, link.recv_frame())
            .await
            .expect("end of stream did not time out")
            .expect("end of stream read");

        (first, second, third)
    });

    let link = tokio::time::timeout(TIMEOUT, dialler.dial(listener_id))
        .await
        .expect("dial did not time out")
        .expect("dial by endpoint id succeeded");
    assert_eq!(
        link.remote(),
        listener_id,
        "link addresses the dialled peer"
    );

    let (mut sender, mut receiver) = link.into_split();
    sender.send_frame(&small).await.expect("small frame sent");
    sender.send_frame(&large).await.expect("large frame sent");

    let got_reply = tokio::time::timeout(TIMEOUT, receiver.recv_frame())
        .await
        .expect("reply did not time out")
        .expect("reply read")
        .expect("stream not finished");
    assert_eq!(got_reply.len(), reply.len(), "reply length preserved");
    assert_eq!(got_reply, reply, "reply payload preserved");

    sender.finish();

    let (first, second, third) = accepted.await.expect("acceptor task finished");

    assert_eq!(first.len(), SMALL, "first frame is exactly {SMALL} bytes");
    assert_eq!(second.len(), LARGE, "second frame is exactly {LARGE} bytes");
    assert_eq!(first, expected_small, "first frame payload preserved");
    assert_eq!(second, expected_large, "second frame payload preserved");
    assert!(
        third.is_none(),
        "exactly two frames arrived; a third read yielded {:?} bytes",
        third.map(|f| f.len())
    );
}

#[tokio::test]
async fn a_supplied_secret_key_fixes_the_endpoint_id() {
    let secret = SecretKey::generate();
    let expected = secret.public();

    let endpoint =
        SidecarEndpoint::bind(SidecarConfig::offline_loopback().with_secret_key(secret.clone()))
            .await
            .expect("bind with a supplied secret key");

    assert_eq!(
        endpoint.id(),
        expected,
        "endpoint id is the key's public half"
    );
    assert_eq!(
        endpoint.secret_key().to_bytes(),
        secret.to_bytes(),
        "the supplied key is the one in use"
    );
    assert!(
        !endpoint.bound_sockets().is_empty(),
        "a bound endpoint reports its sockets"
    );
    endpoint.close().await;
}

#[tokio::test]
async fn a_generated_secret_key_yields_a_distinct_endpoint() {
    let a = SidecarEndpoint::bind(SidecarConfig::offline_loopback())
        .await
        .expect("bind a");
    let b = SidecarEndpoint::bind(SidecarConfig::offline_loopback())
        .await
        .expect("bind b");
    assert_ne!(a.id(), b.id(), "generated keys differ");
    a.close().await;
    b.close().await;
}
