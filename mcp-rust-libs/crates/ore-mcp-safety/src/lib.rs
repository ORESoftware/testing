//! Bounded-output, redaction, and validation primitives for MCP servers.
//!
//! This crate intentionally has no runtime, protocol, HTTP, telemetry, or
//! serialization dependencies. It is the lowest-level dependency in the
//! shared MCP workspace.

use std::{borrow::Cow, fmt};

/// A non-negative byte limit used to make output bounds explicit in APIs.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd, Hash)]
pub struct ByteLimit(usize);

impl ByteLimit {
    /// Creates a byte limit.
    pub const fn new(bytes: usize) -> Self {
        Self(bytes)
    }

    /// Returns the configured number of bytes.
    pub const fn get(self) -> usize {
        self.0
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

impl<'a> TruncatedText<'a> {
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
    let normalized = normalize_key(key);
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

/// A stable, non-secret error envelope for MCP tool results and diagnostics.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct SafeError {
    code: &'static str,
    status: Option<u16>,
}

impl SafeError {
    /// Creates an error with a stable machine-readable code.
    pub const fn new(code: &'static str) -> Self {
        Self { code, status: None }
    }

    /// Adds an HTTP-like status code without adding an upstream body or URL.
    pub const fn with_status(mut self, status: u16) -> Self {
        self.status = Some(status);
        self
    }

    /// Returns the stable machine-readable code.
    pub const fn code(&self) -> &'static str {
        self.code
    }

    /// Returns the optional status code.
    pub const fn status(&self) -> Option<u16> {
        self.status
    }
}

impl fmt::Display for SafeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self.status {
            Some(status) => write!(formatter, "{} (status={status})", self.code),
            None => formatter.write_str(self.code),
        }
    }
}

impl std::error::Error for SafeError {}

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
}
