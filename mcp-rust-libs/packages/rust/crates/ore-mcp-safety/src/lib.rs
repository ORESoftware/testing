//! Bounded-output, redaction, and validation primitives for MCP servers.
//!
//! This crate intentionally has no runtime, protocol, HTTP, or telemetry
//! dependencies. It is the lowest-level handwritten policy crate in the
//! shared MCP workspace; the canonical generated `SafeError` comes from
//! `ore-mcp-contracts`.

#![forbid(unsafe_code)]

pub use ore_mcp_contracts::SafeError;
use serde::{Deserialize, Serialize};
use std::{borrow::Cow, fmt, time::Duration};

/// A non-negative byte limit used to make output bounds explicit in APIs.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct ByteLimit(usize);

impl ByteLimit {
    /// Creates a byte limit.
    pub const fn new(bytes: usize) -> Self {
        Self(bytes)
    }

    /// Returns a validated non-zero byte limit.
    pub const fn try_new(bytes: usize) -> Option<Self> {
        if bytes == 0 { None } else { Some(Self(bytes)) }
    }

    /// Returns the configured number of bytes.
    pub const fn get(self) -> usize {
        self.0
    }

    /// Returns whether the configured budget is zero.
    pub const fn is_zero(self) -> bool {
        self.0 == 0
    }
}

/// Describes an attempted append that would cross a configured byte limit.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct LimitExceeded {
    limit: ByteLimit,
    attempted: usize,
}

impl LimitExceeded {
    /// Returns the configured limit.
    pub const fn limit(self) -> ByteLimit {
        self.limit
    }

    /// Returns the total length that the operation attempted to create.
    pub const fn attempted(self) -> usize {
        self.attempted
    }
}

impl fmt::Display for LimitExceeded {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "byte limit exceeded (limit={}, attempted={})",
            self.limit.get(),
            self.attempted
        )
    }
}

impl std::error::Error for LimitExceeded {}

/// Appends a chunk only when checked arithmetic proves it fits within `limit`.
///
/// The destination is left unchanged when the append would overflow `usize`
/// or exceed the configured limit.
pub fn append_bounded(
    destination: &mut Vec<u8>,
    chunk: &[u8],
    limit: ByteLimit,
) -> Result<(), LimitExceeded> {
    let attempted = destination
        .len()
        .checked_add(chunk.len())
        .ok_or(LimitExceeded {
            limit,
            attempted: usize::MAX,
        })?;
    if attempted > limit.get() {
        return Err(LimitExceeded { limit, attempted });
    }
    destination.extend_from_slice(chunk);
    Ok(())
}

/// A UTF-8-safe view of text truncated to a byte budget.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TruncatedText<'a> {
    text: Cow<'a, str>,
    omitted_bytes: usize,
}

impl TruncatedText<'_> {
    /// Returns the retained text.
    pub fn as_str(&self) -> &str {
        self.text.as_ref()
    }

    /// Returns the number of original bytes omitted from the retained text.
    pub const fn omitted_bytes(&self) -> usize {
        self.omitted_bytes
    }

    /// Returns whether any bytes were omitted.
    pub const fn was_truncated(&self) -> bool {
        self.omitted_bytes != 0
    }

    /// Converts the retained text into an owned string.
    pub fn into_owned(self) -> String {
        self.text.into_owned()
    }
}

/// Truncates UTF-8 text without splitting a code point.
#[must_use]
pub fn truncate_utf8(input: &str, limit: ByteLimit) -> TruncatedText<'_> {
    if input.len() <= limit.get() {
        return TruncatedText {
            text: Cow::Borrowed(input),
            omitted_bytes: 0,
        };
    }

    let mut end = limit.get().min(input.len());
    while end > 0 && !input.is_char_boundary(end) {
        end -= 1;
    }
    TruncatedText {
        text: Cow::Borrowed(&input[..end]),
        omitted_bytes: input.len() - end,
    }
}

/// Returns a command-line option name without an attached value.
///
/// This is suitable for diagnostics because `--token=secret` becomes
/// `--token`. Positional values are represented as `<positional>` rather than
/// echoed back to stderr.
#[must_use]
pub fn sanitized_cli_token(argument: &str) -> String {
    if argument == "--" {
        return "--".to_owned();
    }
    if argument.starts_with('-') {
        return argument
            .split_once('=')
            .map_or_else(|| argument.to_owned(), |(name, _)| name.to_owned());
    }
    "<positional>".to_owned()
}

/// Returns whether a key is likely to identify credentials, secrets, or a
/// natural person and therefore must not be recorded in shared telemetry.
#[must_use]
pub fn is_sensitive_key(key: &str) -> bool {
    const NEEDLES: [&str; 22] = [
        "api_key",
        "apikey",
        "authorization",
        "bearer",
        "cookie",
        "credential",
        "email",
        "jwt",
        "passphrase",
        "passwd",
        "password",
        "phone",
        "private_key",
        "pwd",
        "secret",
        "session",
        "signing_key",
        "ssn",
        "token",
        "user_id",
        "userid",
        "username",
    ];

    let normalized = normalize_key(key);
    NEEDLES.iter().any(|needle| normalized.contains(needle))
}

fn normalize_key(key: &str) -> String {
    key.to_ascii_lowercase().replace(['-', '.', ' '], "_")
}

/// Returns whether an OpenTelemetry-style attribute key is bounded and uses
/// only a conservative ASCII character set.
#[must_use]
pub fn valid_attribute_key(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// Returns whether an OpenTelemetry-style attribute value is bounded and free
/// of control characters.
#[must_use]
pub fn valid_attribute_value(value: &str) -> bool {
    !value.is_empty() && value.len() <= 256 && !value.chars().any(char::is_control)
}

/// A non-zero item-count limit for bounded collections.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct ItemLimit(usize);

impl ItemLimit {
    /// Creates a validated non-zero item limit.
    pub const fn new(items: usize) -> Option<Self> {
        if items == 0 { None } else { Some(Self(items)) }
    }

    /// Returns the configured item count.
    pub const fn get(self) -> usize {
        self.0
    }
}

/// A non-zero nesting-depth limit.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct DepthLimit(usize);

impl DepthLimit {
    /// Creates a validated non-zero depth limit.
    pub const fn new(depth: usize) -> Option<Self> {
        if depth == 0 { None } else { Some(Self(depth)) }
    }

    /// Returns the configured depth.
    pub const fn get(self) -> usize {
        self.0
    }
}

/// A non-zero timeout bound.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct TimeoutLimit(Duration);

impl TimeoutLimit {
    /// Creates a validated non-zero timeout.
    pub const fn new(timeout: Duration) -> Option<Self> {
        if timeout.is_zero() {
            None
        } else {
            Some(Self(timeout))
        }
    }

    /// Returns the configured timeout.
    pub const fn get(self) -> Duration {
        self.0
    }
}

/// An owned byte buffer whose growth is checked against one immutable budget.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct BoundedBytes {
    limit: ByteLimit,
    bytes: Vec<u8>,
}

impl BoundedBytes {
    /// Creates an empty bounded buffer.
    pub const fn new(limit: ByteLimit) -> Self {
        Self {
            limit,
            bytes: Vec::new(),
        }
    }

    /// Creates a bounded buffer without trusting the requested capacity beyond
    /// the configured limit.
    pub fn with_capacity(limit: ByteLimit, requested: usize) -> Self {
        Self {
            limit,
            bytes: Vec::with_capacity(requested.min(limit.get())),
        }
    }

    /// Appends one chunk atomically when it fits.
    pub fn push(&mut self, chunk: &[u8]) -> Result<(), LimitExceeded> {
        append_bounded(&mut self.bytes, chunk, self.limit)
    }

    /// Returns the current byte length.
    pub const fn len(&self) -> usize {
        self.bytes.len()
    }

    /// Returns whether the buffer is empty.
    pub const fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }

    /// Consumes the wrapper and returns its bounded bytes.
    pub fn into_inner(self) -> Vec<u8> {
        self.bytes
    }
}

/// An owned, serialization-friendly truncation result.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct OwnedTruncatedText {
    /// Retained text, optionally with the stable truncation suffix.
    pub value: String,
    /// Whether any bytes were omitted.
    pub truncated: bool,
    /// Number of original UTF-8 bytes omitted before adding the suffix.
    pub omitted_bytes: usize,
}

/// Truncates owned text without splitting a Unicode scalar and, when room
/// permits, appends a stable marker while remaining inside the byte limit.
#[must_use]
pub fn truncate_utf8_owned(value: impl Into<String>, limit: ByteLimit) -> OwnedTruncatedText {
    const SUFFIX: &str = "…[truncated]";
    let mut value = value.into();
    let original_len = value.len();
    if original_len <= limit.get() {
        return OwnedTruncatedText {
            value,
            truncated: false,
            omitted_bytes: 0,
        };
    }

    let suffix = if limit.get() >= SUFFIX.len() {
        SUFFIX
    } else {
        ""
    };
    let mut end = limit.get().saturating_sub(suffix.len()).min(value.len());
    while end > 0 && !value.is_char_boundary(end) {
        end -= 1;
    }
    value.truncate(end);
    value.push_str(suffix);
    OwnedTruncatedText {
        value,
        truncated: true,
        omitted_bytes: original_len.saturating_sub(end),
    }
}

/// Compatibility name for secret/identity key detection.
#[must_use]
pub fn is_sensitive_name(name: &str) -> bool {
    is_sensitive_key(name)
}

/// Returns whether a telemetry key is reserved for validated service identity.
#[must_use]
pub fn is_reserved_identity_name(name: &str) -> bool {
    matches!(
        name.trim().to_ascii_lowercase().as_str(),
        "service.name" | "service.namespace" | "service.version"
    )
}

/// Validates a low-cardinality telemetry label name and rejects sensitive keys.
#[must_use]
pub fn valid_label_name(name: &str) -> bool {
    valid_attribute_key(name) && !is_sensitive_key(name)
}

/// Validates a bounded telemetry label value.
#[must_use]
pub fn valid_label_value(value: &str) -> bool {
    valid_attribute_value(value)
}

/// Validates stable service identity values while excluding whitespace,
/// controls, and free-form user data.
#[must_use]
pub fn valid_service_identity_value(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value.bytes().all(|byte| {
            byte.is_ascii_alphanumeric()
                || matches!(byte, b'.' | b'_' | b':' | b'/' | b'+' | b'@' | b'-')
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounded_append_is_atomic_at_the_limit() {
        let mut output = b"1234".to_vec();
        append_bounded(&mut output, b"56", ByteLimit::new(6)).expect("exact limit fits");
        let error = append_bounded(&mut output, b"7", ByteLimit::new(6))
            .expect_err("one byte too many must fail");
        assert_eq!(output, b"123456");
        assert_eq!(error.limit(), ByteLimit::new(6));
        assert_eq!(error.attempted(), 7);
    }

    #[test]
    fn truncation_never_splits_utf8() {
        let text = "aé🦀z";
        let truncated = truncate_utf8(text, ByteLimit::new(4));
        assert_eq!(truncated.as_str(), "aé");
        assert_eq!(truncated.omitted_bytes(), text.len() - "aé".len());
        assert!(truncated.was_truncated());
    }

    #[test]
    fn cli_diagnostics_do_not_echo_values() {
        assert_eq!(sanitized_cli_token("--token=super-secret"), "--token");
        assert_eq!(sanitized_cli_token("unexpected-secret"), "<positional>");
    }

    #[test]
    fn sensitive_keys_cover_credentials_and_identity() {
        for key in [
            "api.token",
            "AUTHORIZATION",
            "private-key",
            "customer_email",
            "user.id",
        ] {
            assert!(is_sensitive_key(key), "expected sensitive key: {key}");
        }
        assert!(!is_sensitive_key("cloud.region"));
    }

    #[test]
    fn attribute_validation_is_bounded() {
        assert!(valid_attribute_key("cloud.region"));
        assert!(!valid_attribute_key("bad/key"));
        assert!(valid_attribute_value("us-east-1"));
        assert!(!valid_attribute_value("line\nfeed"));
        assert!(!valid_attribute_value(&"x".repeat(257)));
    }
    #[test]
    fn owned_truncation_and_limits_preserve_bounds() {
        assert!(ItemLimit::new(0).is_none());
        assert!(DepthLimit::new(0).is_none());
        assert!(TimeoutLimit::new(Duration::ZERO).is_none());
        let mut bytes = BoundedBytes::new(ByteLimit::new(4));
        bytes.push(b"1234").expect("exact limit fits");
        assert!(bytes.push(b"5").is_err());
        let truncated = truncate_utf8_owned("ab🙂cd", ByteLimit::new(5));
        assert!(truncated.truncated);
        assert!(truncated.value.is_char_boundary(truncated.value.len()));
        assert!(truncated.value.len() <= 5);
    }

    #[test]
    fn label_policy_rejects_sensitive_and_free_form_identity() {
        assert!(valid_label_name("cloud.region"));
        assert!(!valid_label_name("api.token"));
        assert!(valid_service_identity_value(
            "canonical-mcp-server/1.2.3+build"
        ));
        assert!(!valid_service_identity_value("user supplied label"));
    }
}
