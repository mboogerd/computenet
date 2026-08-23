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
    SecretKey, TransportAddr,
};

use crate::{
    error::{Error, Result},
    link::{Link, LinkId, PendingLink},
};

/// The ALPN this sidecar speaks. Peers that do not offer it are refused during
/// the QUIC handshake, before any link exists.
pub const ALPN: &[u8] = b"computenet/sidecar/0";

/// Where a bound endpoint looks up peer addresses it was not handed directly.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum LookupMode {
    /// Only addresses supplied locally via [`SidecarEndpoint::add_peer`] are
    /// resolvable, and no relay is used. Nothing leaves the host's own network,
    /// which is what makes the in-process tests deterministic and offline.
    Offline,
    /// number 0's public relays and DNS/pkarr address lookup — the deployment
    /// default, and the only mode that reaches a peer whose address is unknown.
    #[default]
    N0,
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

        let mut builder = match config.lookup {
            LookupMode::Offline => Endpoint::builder(presets::Minimal),
            LookupMode::N0 => Endpoint::builder(presets::N0),
        };
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
