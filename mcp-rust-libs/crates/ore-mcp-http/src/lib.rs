//! Hardened, credential-free HTTP primitives for public MCP diagnostics.
//!
//! The client requires HTTPS except for loopback development endpoints,
//! rejects embedded URL credentials, disables redirects, uses explicit
//! timeouts, and streams successful response bodies into a checked byte
//! budget. Error display values never contain URLs or upstream bodies.

use std::{error::Error, fmt, time::Duration};

use ore_mcp_safety::{ByteLimit, append_bounded};
use reqwest::{Client, redirect::Policy};
use url::{Host, Url};

/// A validated diagnostic base URL with query and fragment components removed.
#[derive(Clone)]
pub struct BaseUrl(Url);

impl BaseUrl {
    /// Parses and validates a diagnostic base URL.
    pub fn parse(raw: &str) -> Result<Self, DiagnosticError> {
        let mut url = Url::parse(raw).map_err(|_| DiagnosticError::InvalidBaseUrl)?;
        let loopback = is_loopback(&url);
        if url.scheme() != "https" && !(url.scheme() == "http" && loopback) {
            return Err(DiagnosticError::PlaintextRemote);
        }
        if !url.username().is_empty() || url.password().is_some() {
            return Err(DiagnosticError::EmbeddedCredentials);
        }
        if url.cannot_be_a_base() || url.host().is_none() {
            return Err(DiagnosticError::InvalidBaseUrl);
        }
        url.set_query(None);
        url.set_fragment(None);
        if !url.path().ends_with('/') {
            let path = format!("{}/", url.path());
            url.set_path(&path);
        }
        Ok(Self(url))
    }

    fn join(&self, endpoint: &EndpointPath) -> Result<Url, DiagnosticError> {
        self.0
            .join(endpoint.as_str())
            .map_err(|_| DiagnosticError::InvalidEndpoint)
    }
}

fn is_loopback(url: &Url) -> bool {
    match url.host() {
        Some(Host::Ipv4(address)) => address.is_loopback(),
        Some(Host::Ipv6(address)) => address.is_loopback(),
        Some(Host::Domain(domain)) => {
            domain.eq_ignore_ascii_case("localhost")
                || domain
                    .to_ascii_lowercase()
                    .strip_suffix(".localhost")
                    .is_some_and(|prefix| !prefix.is_empty())
        }
        None => false,
    }
}

/// A relative endpoint path that cannot replace the validated base URL.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EndpointPath(String);

impl EndpointPath {
    /// Validates a relative endpoint path.
    pub fn parse(value: impl Into<String>) -> Result<Self, DiagnosticError> {
        let value = value.into();
        let invalid = value.is_empty()
            || value.starts_with('/')
            || value.contains(['\\', '?', '#', ':'])
            || value
                .split('/')
                .any(|segment| segment.is_empty() || matches!(segment, "." | ".."));
        if invalid {
            return Err(DiagnosticError::InvalidEndpoint);
        }
        Ok(Self(value))
    }

    /// Returns the validated relative path.
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

/// Construction policy for a credential-free diagnostic client.
#[derive(Clone, Debug)]
pub struct DiagnosticClientConfig {
    user_agent: String,
    connect_timeout: Duration,
    request_timeout: Duration,
    response_limit: ByteLimit,
}

impl DiagnosticClientConfig {
    /// Creates a policy with conservative defaults.
    pub fn new(user_agent: impl Into<String>) -> Self {
        Self {
            user_agent: user_agent.into(),
            connect_timeout: Duration::from_secs(2),
            request_timeout: Duration::from_secs(5),
            response_limit: ByteLimit::new(1_048_576),
        }
    }

    /// Sets the connection timeout.
    #[must_use]
    pub const fn with_connect_timeout(mut self, timeout: Duration) -> Self {
        self.connect_timeout = timeout;
        self
    }

    /// Sets the total request timeout.
    #[must_use]
    pub const fn with_request_timeout(mut self, timeout: Duration) -> Self {
        self.request_timeout = timeout;
        self
    }

    /// Sets the maximum successful response-body size.
    #[must_use]
    pub const fn with_response_limit(mut self, limit: ByteLimit) -> Self {
        self.response_limit = limit;
        self
    }
}

/// A public diagnostic HTTP client that has no API for attaching credentials.
#[derive(Clone)]
pub struct DiagnosticClient {
    client: Client,
    response_limit: ByteLimit,
}

impl DiagnosticClient {
    /// Builds a client with redirects disabled and explicit timeouts.
    pub fn new(config: DiagnosticClientConfig) -> Result<Self, DiagnosticError> {
        let client = Client::builder()
            .redirect(Policy::none())
            .connect_timeout(config.connect_timeout)
            .timeout(config.request_timeout)
            .user_agent(config.user_agent)
            .build()
            .map_err(|_| DiagnosticError::BuildClient)?;
        Ok(Self {
            client,
            response_limit: config.response_limit,
        })
    }

    /// Fetches one successful response into a checked byte budget.
    pub async fn get_bytes(
        &self,
        base: &BaseUrl,
        endpoint: &EndpointPath,
    ) -> Result<DiagnosticResponse, DiagnosticError> {
        let url = base.join(endpoint)?;
        let mut response = self
            .client
            .get(url)
            .send()
            .await
            .map_err(|_| DiagnosticError::RequestFailed)?;
        let status = response.status().as_u16();
        if !(200..300).contains(&status) {
            return Err(DiagnosticError::UpstreamStatus(status));
        }
        if response
            .content_length()
            .is_some_and(|length| length > self.response_limit.get() as u64)
        {
            return Err(DiagnosticError::ResponseTooLarge(status));
        }

        let capacity = response
            .content_length()
            .unwrap_or_default()
            .min(self.response_limit.get() as u64) as usize;
        let mut body = Vec::with_capacity(capacity);
        loop {
            match response.chunk().await {
                Ok(Some(chunk)) => append_bounded(&mut body, &chunk, self.response_limit)
                    .map_err(|_| DiagnosticError::ResponseTooLarge(status))?,
                Ok(None) => break,
                Err(_) => return Err(DiagnosticError::InvalidResponse(status)),
            }
        }
        Ok(DiagnosticResponse { status, body })
    }
}

/// A bounded successful diagnostic response.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DiagnosticResponse {
    status: u16,
    body: Vec<u8>,
}

impl DiagnosticResponse {
    /// Returns the upstream status code.
    pub const fn status(&self) -> u16 {
        self.status
    }

    /// Returns the bounded response body.
    pub fn body(&self) -> &[u8] {
        &self.body
    }

    /// Consumes the response and returns its bounded body.
    pub fn into_body(self) -> Vec<u8> {
        self.body
    }
}

/// A stable diagnostic error that excludes URLs, credentials, and bodies.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DiagnosticError {
    /// The base URL was malformed or not hierarchical.
    InvalidBaseUrl,
    /// Remote plaintext HTTP was rejected.
    PlaintextRemote,
    /// Embedded username or password data was rejected.
    EmbeddedCredentials,
    /// The requested endpoint was not a safe relative path.
    InvalidEndpoint,
    /// The credential-free HTTP client could not be built.
    BuildClient,
    /// The request failed or timed out.
    RequestFailed,
    /// A successful response exceeded its byte budget.
    ResponseTooLarge(u16),
    /// The response stream could not be read safely.
    InvalidResponse(u16),
    /// The upstream returned a non-success status.
    UpstreamStatus(u16),
}

impl fmt::Display for DiagnosticError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidBaseUrl => formatter.write_str("invalid_base_url"),
            Self::PlaintextRemote => formatter.write_str("remote_plaintext_http_rejected"),
            Self::EmbeddedCredentials => formatter.write_str("embedded_credentials_rejected"),
            Self::InvalidEndpoint => formatter.write_str("invalid_endpoint"),
            Self::BuildClient => formatter.write_str("client_initialization_failed"),
            Self::RequestFailed => formatter.write_str("authority_unavailable"),
            Self::ResponseTooLarge(status) => {
                write!(formatter, "response_too_large (status={status})")
            }
            Self::InvalidResponse(status) => {
                write!(formatter, "invalid_response (status={status})")
            }
            Self::UpstreamStatus(status) => {
                write!(formatter, "authority_error (status={status})")
            }
        }
    }
}

impl Error for DiagnosticError {}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpListener,
    };

    async fn one_response(response: &'static [u8]) -> String {
        let listener = TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind test listener");
        let address = listener.local_addr().expect("test address");
        tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.expect("accept request");
            let mut request = [0_u8; 2048];
            let _ = stream.read(&mut request).await.expect("read request");
            stream.write_all(response).await.expect("write response");
            stream.shutdown().await.expect("close response");
        });
        format!("http://{address}/")
    }

    #[test]
    fn rejects_remote_plaintext_and_embedded_credentials() {
        assert_eq!(
            BaseUrl::parse("http://auth.example.com").err(),
            Some(DiagnosticError::PlaintextRemote)
        );
        let error = BaseUrl::parse("https://operator:secret@auth.example.com")
            .err()
            .expect("embedded credentials must fail");
        assert_eq!(error, DiagnosticError::EmbeddedCredentials);
        assert!(!error.to_string().contains("secret"));
    }

    #[test]
    fn accepts_loopback_http_and_strips_query_fragment() {
        BaseUrl::parse("http://127.0.0.2:8080/base?token=nope#fragment")
            .expect("loopback development URL is allowed");
        BaseUrl::parse("http://dev.localhost:8080").expect("localhost subdomain is loopback");
    }

    #[test]
    fn endpoint_cannot_escape_or_replace_base() {
        for value in [
            "/healthz",
            "../secret",
            "https://evil.example",
            "a//b",
            "a?x=1",
        ] {
            assert_eq!(
                EndpointPath::parse(value).err(),
                Some(DiagnosticError::InvalidEndpoint)
            );
        }
        EndpointPath::parse(".well-known/jwks.json").expect("safe relative endpoint");
    }

    #[tokio::test]
    async fn reads_a_bounded_successful_body() {
        let base =
            BaseUrl::parse(&one_response(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok").await)
                .expect("test base URL");
        let endpoint = EndpointPath::parse("healthz").expect("endpoint");
        let client = DiagnosticClient::new(
            DiagnosticClientConfig::new("ore-mcp-http-test/0.1")
                .with_response_limit(ByteLimit::new(16)),
        )
        .expect("client");
        let response = client
            .get_bytes(&base, &endpoint)
            .await
            .expect("bounded response");
        assert_eq!(response.status(), 200);
        assert_eq!(response.body(), b"ok");
    }

    #[tokio::test]
    async fn rejects_declared_and_streamed_oversized_bodies() {
        let declared =
            BaseUrl::parse(&one_response(b"HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n").await)
                .expect("test base URL");
        let endpoint = EndpointPath::parse("healthz").expect("endpoint");
        let client = DiagnosticClient::new(
            DiagnosticClientConfig::new("ore-mcp-http-test/0.1")
                .with_response_limit(ByteLimit::new(4)),
        )
        .expect("client");
        assert_eq!(
            client.get_bytes(&declared, &endpoint).await.err(),
            Some(DiagnosticError::ResponseTooLarge(200))
        );

        let streamed = BaseUrl::parse(
            &one_response(
                b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n3\r\ndef\r\n0\r\n\r\n",
            )
            .await,
        )
        .expect("test base URL");
        assert_eq!(
            client.get_bytes(&streamed, &endpoint).await.err(),
            Some(DiagnosticError::ResponseTooLarge(200))
        );
    }

    #[tokio::test]
    async fn redirect_is_not_followed() {
        let base = BaseUrl::parse(
            &one_response(
                b"HTTP/1.1 302 Found\r\nLocation: https://evil.example/\r\nContent-Length: 0\r\n\r\n",
            )
            .await,
        )
        .expect("test base URL");
        let endpoint = EndpointPath::parse("healthz").expect("endpoint");
        let client =
            DiagnosticClient::new(DiagnosticClientConfig::new("test/0.1")).expect("client");
        assert_eq!(
            client.get_bytes(&base, &endpoint).await.err(),
            Some(DiagnosticError::UpstreamStatus(302))
        );
    }
}
