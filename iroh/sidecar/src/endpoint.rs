//! The sidecar endpoint: binds an iroh endpoint, accepts links, dials by id.

use std::{
    net::SocketAddr,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
};

use iroh::{
    address_lookup::memory::MemoryLookup, endpoint::presets, Endpoint, EndpointAddr, EndpointId,
    RelayMap, RelayMode, RelayUrl, SecretKey, TransportAddr,
};

use crate::{
    error::{Error, Result},
    link::{Link, LinkId, PendingLink},
};

/// The ALPN this sidecar speaks. Peers that do not offer it are refused during
/// the QUIC handshake, before any link exists.
pub const ALPN: &[u8] = b"computenet/sidecar/0";

/// Where a bound endpoint looks up peer addresses it was not handed directly.
///
/// Deliberately not `Copy`: [`LookupMode::Relay`] carries a [`RelayUrl`].
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum LookupMode {
    /// Only addresses supplied locally via [`SidecarEndpoint::add_peer`] are
    /// resolvable, and no relay is used. Nothing leaves the host's own network,
    /// which is what makes the in-process tests deterministic and offline.
    Offline,
    /// number 0's public relays and DNS/pkarr address lookup — the deployment
    /// default, and the only mode that reaches a peer whose address is unknown.
    #[default]
    N0,
    /// Exactly one operator-supplied relay, and no address lookup service at
    /// all. Peer addresses come from [`SidecarEndpoint::add_peer`] and from a
    /// peer's own relay address; nothing is published to or resolved from n0's
    /// DNS/pkarr infrastructure. This is the mode CI uses against a
    /// self-hosted relay.
    Relay(RelayUrl),
}

impl LookupMode {
    /// The relay configuration this mode adds on top of its preset, or `None`
    /// when the preset's own relay behaviour stands.
    ///
    /// Only [`LookupMode::Relay`] overrides: it pins the endpoint to exactly
    /// the one configured relay. [`LookupMode::Offline`] keeps
    /// `presets::Minimal`'s disabled relay and [`LookupMode::N0`] keeps
    /// `presets::N0`'s public relay map.
    pub fn relay_override(&self) -> Option<RelayMode> {
        match self {
            LookupMode::Offline | LookupMode::N0 => None,
            LookupMode::Relay(url) => Some(RelayMode::Custom(RelayMap::from_iter([url.clone()]))),
        }
    }
}

/// How to bind a [`SidecarEndpoint`].
#[derive(Debug, Default)]
pub struct SidecarConfig {
    /// The ed25519 secret key. `None` generates a fresh one, which makes a fresh
    /// [`EndpointId`]; supply one to keep a stable identity across restarts.
    pub secret_key: Option<SecretKey>,
    /// Address lookup and relay behaviour.
    pub lookup: LookupMode,
    /// Sockets to bind. Empty means iroh's default (`0.0.0.0:0` plus `[::]:0`).
    pub bind_addrs: Vec<SocketAddr>,
    /// ALPN to speak. Empty means [`ALPN`].
    pub alpn: Vec<u8>,
}

impl SidecarConfig {
    /// A config that binds loopback only and never talks to a relay or a DNS
    /// server — for tests and for host-local use.
    pub fn offline_loopback() -> Self {
        SidecarConfig {
            lookup: LookupMode::Offline,
            bind_addrs: vec!["127.0.0.1:0".parse().expect("literal loopback addr")],
            ..Default::default()
        }
    }

    /// Uses this secret key rather than generating one.
    pub fn with_secret_key(mut self, secret_key: SecretKey) -> Self {
        self.secret_key = Some(secret_key);
        self
    }
}

/// A bound iroh endpoint that accepts and dials sidecar links.
///
/// Cheap to clone; clones share the underlying endpoint and its link-id counter.
#[derive(Debug, Clone)]
pub struct SidecarEndpoint {
    endpoint: Endpoint,
    lookup: MemoryLookup,
    alpn: Arc<Vec<u8>>,
    next_link_id: Arc<AtomicU64>,
}

impl SidecarEndpoint {
    /// Binds an endpoint per `config`.
    pub async fn bind(config: SidecarConfig) -> Result<Self> {
        let alpn = if config.alpn.is_empty() {
            ALPN.to_vec()
        } else {
            config.alpn.clone()
        };
        let lookup = MemoryLookup::new();

        // `Relay` shares `Offline`'s minimal preset — no DNS/pkarr address
        // lookup service — and then replaces its disabled relay with exactly
        // the configured one. Everything after this point is identical across
        // the three modes.
        let mut builder = match &config.lookup {
            LookupMode::Offline | LookupMode::Relay(_) => Endpoint::builder(presets::Minimal),
            LookupMode::N0 => Endpoint::builder(presets::N0),
        };
        if let Some(relay_mode) = config.lookup.relay_override() {
            builder = builder.relay_mode(relay_mode);
        }
        builder = builder
            .alpns(vec![alpn.clone()])
            .address_lookup(lookup.clone());
        if let Some(secret_key) = config.secret_key {
            builder = builder.secret_key(secret_key);
        }
        if !config.bind_addrs.is_empty() {
            builder = builder.clear_ip_transports();
            for addr in &config.bind_addrs {
                builder = builder
                    .bind_addr(*addr)
                    .map_err(|e| Error::Bind(Box::new(e)))?;
            }
        }

        let endpoint = builder.bind().await.map_err(|e| Error::Bind(Box::new(e)))?;

        Ok(SidecarEndpoint {
            endpoint,
            lookup,
            alpn: Arc::new(alpn),
            next_link_id: Arc::new(AtomicU64::new(1)),
        })
    }

    /// This endpoint's id — its ed25519 public key, and the address peers dial.
    pub fn id(&self) -> EndpointId {
        self.endpoint.id()
    }

    /// This endpoint's secret key.
    pub fn secret_key(&self) -> &SecretKey {
        self.endpoint.secret_key()
    }

    /// The ALPN this endpoint speaks.
    pub fn alpn(&self) -> &[u8] {
        &self.alpn
    }

    /// The addressing information iroh currently believes reaches this endpoint.
    ///
    /// Under [`LookupMode::N0`] this fills in over the first seconds of life as
    /// the relay and address lookup report back; under [`LookupMode::Offline`]
    /// use [`SidecarEndpoint::bound_addr`], which is exact from the moment the
    /// endpoint binds.
    pub fn addr(&self) -> EndpointAddr {
        self.endpoint.addr()
    }

    /// This endpoint's id together with the sockets it is actually bound to.
    ///
    /// Hand this to a peer's [`SidecarEndpoint::add_peer`] and it can dial this
    /// endpoint by id without any relay or name lookup.
    pub fn bound_addr(&self) -> EndpointAddr {
        EndpointAddr::from_parts(
            self.endpoint.id(),
            self.endpoint
                .bound_sockets()
                .into_iter()
                .map(TransportAddr::Ip),
        )
    }

    /// The sockets this endpoint is bound to.
    pub fn bound_sockets(&self) -> Vec<SocketAddr> {
        self.endpoint.bound_sockets()
    }

    /// Teaches this endpoint how to reach a peer, so [`SidecarEndpoint::dial`]
    /// can take the bare [`EndpointId`].
    pub fn add_peer(&self, addr: EndpointAddr) {
        self.lookup.add_endpoint_info(addr);
    }

    /// Dials a peer **by its id** and establishes the link's one bi-directional
    /// stream.
    ///
    /// The address behind the id comes from whatever was supplied to
    /// [`SidecarEndpoint::add_peer`] and from the configured [`LookupMode`].
    pub async fn dial(&self, peer: EndpointId) -> Result<Link> {
        let conn = self
            .endpoint
            .connect(peer, &self.alpn)
            .await
            .map_err(|e| Error::Dial(Box::new(e)))?;
        // The dialling side opens the link's single bi-directional stream.
        let (send, recv) = conn
            .open_bi()
            .await
            .map_err(|e| Error::OpenStream(Box::new(e)))?;
        Ok(Link::new(self.next_link_id(), conn, send, recv))
    }

    /// Accepts the next inbound link, adopting the bi-directional stream the
    /// dialler opened. `Ok(None)` once this endpoint is closed.
    ///
    /// This waits for the dialler's *first frame*, because that is when QUIC
    /// reveals the stream. A caller that wants to know a peer has connected
    /// before it says anything uses [`SidecarEndpoint::accept_pending`].
    pub async fn accept(&self) -> Result<Option<Link>> {
        match self.accept_pending().await? {
            Some(pending) => Ok(Some(pending.establish().await?)),
            None => Ok(None),
        }
    }

    /// Accepts the next inbound connection **without** waiting for its stream.
    ///
    /// The returned [`PendingLink`] already knows the peer and can be watched
    /// and closed; [`PendingLink::establish`] adopts the stream once the dialler
    /// writes. `Ok(None)` once this endpoint is closed.
    pub async fn accept_pending(&self) -> Result<Option<PendingLink>> {
        let Some(incoming) = self.endpoint.accept().await else {
            return Ok(None);
        };
        let conn = incoming.await.map_err(|e| Error::Accept(Box::new(e)))?;
        Ok(Some(PendingLink::new(self.next_link_id(), conn)))
    }

    /// Closes the endpoint; any pending [`SidecarEndpoint::accept`] yields
    /// `Ok(None)`.
    pub async fn close(&self) {
        self.endpoint.close().await;
    }

    fn next_link_id(&self) -> LinkId {
        LinkId::new(self.next_link_id.fetch_add(1, Ordering::Relaxed))
    }
}

#[cfg(test)]
mod tests {
    use std::str::FromStr;

    use super::*;

    fn relay_url() -> RelayUrl {
        RelayUrl::from_str("https://relay.example.org").expect("literal relay url")
    }

    fn other_url() -> RelayUrl {
        RelayUrl::from_str("https://other-relay.example.org").expect("literal relay url")
    }

    #[test]
    fn relay_mode_pins_exactly_the_configured_relay() {
        let url = relay_url();
        let mode = LookupMode::Relay(url.clone())
            .relay_override()
            .expect("Relay overrides the preset's relay behaviour");

        assert_eq!(mode, RelayMode::Custom(RelayMap::from_iter([url.clone()])));
        assert_eq!(mode.relay_map().urls::<Vec<_>>(), vec![url]);
    }

    #[test]
    fn offline_and_n0_keep_their_presets_relay_behaviour() {
        // The proof that --relay-url did not move the other two modes: neither
        // overrides, so each keeps its preset's relay map (Minimal's disabled
        // relay, N0's public one).
        assert_eq!(LookupMode::Offline.relay_override(), None);
        assert_eq!(LookupMode::N0.relay_override(), None);
    }

    #[tokio::test]
    async fn a_relay_config_binds_an_endpoint_over_that_relay_alone() {
        // A loopback relay url: nothing here reaches the network, and no relay
        // needs to be listening for the endpoint to bind.
        let url = RelayUrl::from_str("https://127.0.0.1:65535").expect("literal relay url");
        let endpoint = SidecarEndpoint::bind(SidecarConfig {
            lookup: LookupMode::Relay(url.clone()),
            bind_addrs: vec!["127.0.0.1:0".parse().expect("literal loopback addr")],
            ..Default::default()
        })
        .await
        .expect("a custom relay is a valid relay map, so bind succeeds");

        // Bound and usable: the id and the loopback socket are the same as in
        // any other mode.
        assert_eq!(endpoint.bound_sockets().len(), 1);
        assert_eq!(endpoint.id(), endpoint.bound_addr().id);

        // And no address lookup service beyond MemoryLookup: `presets::N0`
        // would have added a PkarrPublisher, a PkarrResolver and a
        // DnsAddressLookup on top of it (iroh 1.0.3 src/endpoint/presets.rs),
        // so a count of exactly one is what pins Relay to the minimal preset
        // rather than merely to the right relay map.
        assert_eq!(
            endpoint
                .endpoint
                .address_lookup()
                .expect("the endpoint is open")
                .len(),
            1,
            "MemoryLookup is the only address lookup service: no DNS/pkarr"
        );

        // And the bound endpoint's relay map really is that one relay: iroh's
        // `remove_relay` answers `Some` only for a url the endpoint has
        // configured. Destructive, so it comes last.
        assert!(
            endpoint.endpoint.remove_relay(&other_url()).await.is_none(),
            "no relay other than the configured one"
        );
        assert!(
            endpoint.endpoint.remove_relay(&url).await.is_some(),
            "the configured relay is in the bound endpoint's relay map"
        );

        endpoint.close().await;
    }

    #[tokio::test]
    async fn offline_binds_with_no_relay_at_all() {
        // The other side of the same probe, and the proof --relay-url did not
        // move the offline path: nothing is in Offline's relay map.
        let endpoint = SidecarEndpoint::bind(SidecarConfig::offline_loopback())
            .await
            .expect("offline binds");

        assert!(
            endpoint.endpoint.remove_relay(&relay_url()).await.is_none(),
            "offline configures no relay"
        );
        assert!(
            endpoint.endpoint.remove_relay(&other_url()).await.is_none(),
            "offline configures no relay"
        );

        endpoint.close().await;
    }
}
