//! Bounded, runtime-independent conformance support for stdio MCP servers.
//!
//! The harness spawns a real process, writes newline-delimited JSON-RPC frames,
//! and rejects non-JSON stdout. Every frame, wait, and unsolicited-message loop
//! is bounded. The crate intentionally knows nothing about a product server's
//! tools or authorization policy; repositories retain their own assertions.

#![forbid(unsafe_code)]

use serde::{Deserialize, Serialize};
use std::{
    ffi::{OsStr, OsString},
    fmt,
    path::{Path, PathBuf},
    process::Stdio,
    time::Duration,
};

use ore_mcp_safety::{ByteLimit, append_bounded};
use serde_json::Value;
use tokio::{
    io::{AsyncBufRead, AsyncBufReadExt, AsyncWriteExt, BufReader},
    process::{Child, ChildStderr, ChildStdin, ChildStdout, Command},
    time::timeout,
};

/// Machine-readable result emitted by cross-language fixture runners.
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct FixtureResult {
    /// Stable fixture identifier relative to the canonical corpus.
    pub fixture_id: String,
    /// Whether the implementation accepted the fixture.
    pub accepted: bool,
    /// Stable validation code when rejected.
    pub error_code: Option<String>,
    /// JSON-pointer path when rejected.
    pub error_path: Option<String>,
}

/// Errors from portable fixture and stdout-purity helpers.
#[derive(Debug)]
pub enum TestkitError {
    /// The fixture could not be read.
    Read,
    /// The fixture was not valid JSON.
    InvalidJson,
    /// Stdout contained a non-protocol line at the zero-based line index.
    StdoutPollution(usize),
}

impl fmt::Display for TestkitError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Read => formatter.write_str("fixture could not be read"),
            Self::InvalidJson => formatter.write_str("fixture is not valid JSON"),
            Self::StdoutPollution(index) => {
                write!(
                    formatter,
                    "stdout contains a non-protocol line at index {index}"
                )
            }
        }
    }
}

impl std::error::Error for TestkitError {}

/// Reads one canonical JSON fixture without embedding its content in errors.
pub fn load_json(path: impl AsRef<Path>) -> Result<Value, TestkitError> {
    let bytes = std::fs::read(path).map_err(|_| TestkitError::Read)?;
    serde_json::from_slice(&bytes).map_err(|_| TestkitError::InvalidJson)
}

/// Verifies that every non-empty newline-delimited stdout frame is JSON.
/// Product repositories may layer stricter JSON-RPC shape checks on top.
pub fn assert_json_only_stdout(stdout: &[u8]) -> Result<(), TestkitError> {
    for (index, line) in stdout
        .split(|byte| *byte == b'\n')
        .filter(|line| !line.is_empty())
        .enumerate()
    {
        serde_json::from_slice::<Value>(line).map_err(|_| TestkitError::StdoutPollution(index))?;
    }
    Ok(())
}

/// Default maximum size of one stdout, stdin, or stderr frame.
pub const DEFAULT_MAX_FRAME_BYTES: usize = 1_048_576;

/// Default maximum number of messages inspected while awaiting one response.
pub const DEFAULT_MAX_INTERVENING_MESSAGES: usize = 128;

/// Default timeout for one process I/O operation.
pub const DEFAULT_IO_TIMEOUT: Duration = Duration::from_secs(5);

/// Bounded process and protocol limits for [`StdioHarness`].
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct HarnessLimits {
    max_frame_bytes: ByteLimit,
    max_intervening_messages: usize,
    io_timeout: Duration,
}

impl HarnessLimits {
    /// Creates explicit conformance limits.
    pub const fn new(
        max_frame_bytes: ByteLimit,
        max_intervening_messages: usize,
        io_timeout: Duration,
    ) -> Self {
        Self {
            max_frame_bytes,
            max_intervening_messages,
            io_timeout,
        }
    }

    /// Returns the maximum encoded frame size.
    pub const fn max_frame_bytes(self) -> ByteLimit {
        self.max_frame_bytes
    }

    /// Returns the maximum number of unrelated messages accepted while
    /// awaiting a response.
    pub const fn max_intervening_messages(self) -> usize {
        self.max_intervening_messages
    }

    /// Returns the timeout applied to each process I/O operation.
    pub const fn io_timeout(self) -> Duration {
        self.io_timeout
    }
}

impl Default for HarnessLimits {
    fn default() -> Self {
        Self::new(
            ByteLimit::new(DEFAULT_MAX_FRAME_BYTES),
            DEFAULT_MAX_INTERVENING_MESSAGES,
            DEFAULT_IO_TIMEOUT,
        )
    }
}

/// Stable conformance-harness failure categories.
#[derive(Debug)]
pub enum HarnessError {
    /// The child process could not be spawned or its pipes could not be used.
    ProcessIo(std::io::Error),
    /// The child process did not expose all required stdio pipes.
    MissingPipe(&'static str),
    /// A bounded I/O operation exceeded its timeout.
    Timeout(&'static str),
    /// A protocol or stderr frame exceeded the configured byte limit.
    FrameTooLarge,
    /// Stdout contained a non-JSON frame, violating protocol purity.
    NonJsonStdout,
    /// A request did not contain a valid JSON-RPC identifier.
    MissingRequestId,
    /// The child closed stdout before a matching response arrived.
    UnexpectedEof,
    /// The response wait exceeded the configured intervening-message count.
    TooManyInterveningMessages,
    /// The child emitted a JSON message that was neither a matching response
    /// nor an allowed notification.
    UnexpectedMessage,
    /// A value could not be serialized into a bounded JSON-RPC frame.
    Serialize,
}

impl fmt::Display for HarnessError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::ProcessIo(_) => formatter.write_str("MCP child-process I/O failed"),
            Self::MissingPipe(name) => write!(formatter, "MCP child did not expose {name}"),
            Self::Timeout(operation) => write!(formatter, "MCP {operation} timed out"),
            Self::FrameTooLarge => formatter.write_str("MCP frame exceeded its byte limit"),
            Self::NonJsonStdout => formatter.write_str("MCP stdout contained a non-JSON frame"),
            Self::MissingRequestId => formatter.write_str("MCP request is missing a valid id"),
            Self::UnexpectedEof => formatter.write_str("MCP child closed stdout unexpectedly"),
            Self::TooManyInterveningMessages => {
                formatter.write_str("MCP response exceeded the intervening-message limit")
            }
            Self::UnexpectedMessage => {
                formatter.write_str("MCP child emitted an unexpected JSON-RPC message")
            }
            Self::Serialize => formatter.write_str("MCP message could not be serialized"),
        }
    }
}

impl std::error::Error for HarnessError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::ProcessIo(error) => Some(error),
            _ => None,
        }
    }
}

impl From<std::io::Error> for HarnessError {
    fn from(error: std::io::Error) -> Self {
        Self::ProcessIo(error)
    }
}

/// Builder for a real stdio MCP child process.
#[derive(Debug)]
pub struct StdioCommand {
    program: OsString,
    arguments: Vec<OsString>,
    current_directory: Option<PathBuf>,
    environment: Vec<(OsString, OsString)>,
    clear_environment: bool,
    limits: HarnessLimits,
}

impl StdioCommand {
    /// Creates a command for `program` with inherited environment variables.
    pub fn new(program: impl AsRef<OsStr>) -> Self {
        Self {
            program: program.as_ref().to_owned(),
            arguments: Vec::new(),
            current_directory: None,
            environment: Vec::new(),
            clear_environment: false,
            limits: HarnessLimits::default(),
        }
    }

    /// Appends one command-line argument.
    #[must_use]
    pub fn arg(mut self, argument: impl AsRef<OsStr>) -> Self {
        self.arguments.push(argument.as_ref().to_owned());
        self
    }

    /// Appends several command-line arguments.
    #[must_use]
    pub fn args<I, S>(mut self, arguments: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<OsStr>,
    {
        self.arguments.extend(
            arguments
                .into_iter()
                .map(|argument| argument.as_ref().to_owned()),
        );
        self
    }

    /// Sets the child process working directory.
    #[must_use]
    pub fn current_dir(mut self, directory: impl AsRef<Path>) -> Self {
        self.current_directory = Some(directory.as_ref().to_path_buf());
        self
    }

    /// Adds or replaces one child environment variable.
    #[must_use]
    pub fn env(mut self, key: impl AsRef<OsStr>, value: impl AsRef<OsStr>) -> Self {
        self.environment
            .push((key.as_ref().to_owned(), value.as_ref().to_owned()));
        self
    }

    /// Clears the inherited child environment before applying values supplied
    /// with [`Self::env`].
    #[must_use]
    pub const fn env_clear(mut self) -> Self {
        self.clear_environment = true;
        self
    }

    /// Replaces the default protocol and process limits.
    #[must_use]
    pub const fn limits(mut self, limits: HarnessLimits) -> Self {
        self.limits = limits;
        self
    }

    /// Spawns the child with piped stdin, stdout, and stderr.
    ///
    /// # Errors
    ///
    /// Returns an error when the process cannot be spawned or a required pipe
    /// is unavailable.
    pub fn spawn(self) -> Result<StdioHarness, HarnessError> {
        let mut command = Command::new(self.program);
        command
            .args(self.arguments)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        if let Some(directory) = self.current_directory {
            command.current_dir(directory);
        }
        if self.clear_environment {
            command.env_clear();
        }
        command.envs(self.environment);

        let mut child = command.spawn()?;
        let stdin = child
            .stdin
            .take()
            .ok_or(HarnessError::MissingPipe("stdin"))?;
        let stdout = child
            .stdout
            .take()
            .ok_or(HarnessError::MissingPipe("stdout"))?;
        let stderr = child
            .stderr
            .take()
            .ok_or(HarnessError::MissingPipe("stderr"))?;
        Ok(StdioHarness {
            child,
            stdin,
            stdout: BufReader::new(stdout),
            stderr: BufReader::new(stderr),
            limits: self.limits,
        })
    }
}

/// A running real-process MCP stdio conformance harness.
#[derive(Debug)]
pub struct StdioHarness {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
    stderr: BufReader<ChildStderr>,
    limits: HarnessLimits,
}

impl StdioHarness {
    /// Returns the child process identifier when the platform exposes it.
    pub fn id(&self) -> Option<u32> {
        self.child.id()
    }

    /// Returns the configured limits.
    pub const fn limits(&self) -> HarnessLimits {
        self.limits
    }

    /// Writes one bounded newline-delimited JSON-RPC message to child stdin.
    ///
    /// # Errors
    ///
    /// Returns an error for serialization failure, oversized output, timeout,
    /// or process I/O failure.
    pub async fn send_message(&mut self, message: &Value) -> Result<(), HarnessError> {
        let mut encoded = serde_json::to_vec(message).map_err(|_| HarnessError::Serialize)?;
        if append_bounded(&mut encoded, b"\n", self.limits.max_frame_bytes()).is_err() {
            return Err(HarnessError::FrameTooLarge);
        }
        timeout(self.limits.io_timeout(), self.stdin.write_all(&encoded))
            .await
            .map_err(|_| HarnessError::Timeout("stdin write"))??;
        timeout(self.limits.io_timeout(), self.stdin.flush())
            .await
            .map_err(|_| HarnessError::Timeout("stdin flush"))??;
        Ok(())
    }

    /// Reads and parses one bounded JSON-RPC message from child stdout.
    ///
    /// Any ordinary text on stdout is reported as [`HarnessError::NonJsonStdout`]
    /// because MCP reserves stdout for protocol frames.
    ///
    /// # Errors
    ///
    /// Returns an error for timeout, oversized output, invalid JSON, or process
    /// I/O failure.
    pub async fn read_message(&mut self) -> Result<Option<Value>, HarnessError> {
        let line = timeout(
            self.limits.io_timeout(),
            read_bounded_line(&mut self.stdout, self.limits.max_frame_bytes()),
        )
        .await
        .map_err(|_| HarnessError::Timeout("stdout read"))??;
        line.map(|bytes| serde_json::from_slice(&bytes).map_err(|_| HarnessError::NonJsonStdout))
            .transpose()
    }

    /// Reads one bounded UTF-8-lossy diagnostic line from child stderr.
    ///
    /// # Errors
    ///
    /// Returns an error for timeout, oversized output, or process I/O failure.
    pub async fn read_stderr_line(&mut self) -> Result<Option<String>, HarnessError> {
        let line = timeout(
            self.limits.io_timeout(),
            read_bounded_line(&mut self.stderr, self.limits.max_frame_bytes()),
        )
        .await
        .map_err(|_| HarnessError::Timeout("stderr read"))??;
        Ok(line.map(|bytes| String::from_utf8_lossy(&bytes).into_owned()))
    }

    /// Sends a JSON-RPC request and waits for the matching response.
    ///
    /// Notifications may intervene. Server-initiated requests, unrelated
    /// responses, malformed envelopes, and excessive notification streams fail
    /// closed so conformance tests do not silently ignore protocol surprises.
    ///
    /// # Errors
    ///
    /// Returns an error for an invalid request id, bounded I/O failure,
    /// unexpected EOF/message, or excessive intervening messages.
    pub async fn request(&mut self, request: &Value) -> Result<Value, HarnessError> {
        let request_id = request
            .get("id")
            .filter(|id| id.is_number() || id.is_string())
            .cloned()
            .ok_or(HarnessError::MissingRequestId)?;
        self.send_message(request).await?;

        let mut intervening_messages = 0_usize;
        loop {
            let message = self
                .read_message()
                .await?
                .ok_or(HarnessError::UnexpectedEof)?;
            if message.get("id") == Some(&request_id) {
                return Ok(message);
            }
            if is_notification(&message) {
                if intervening_messages >= self.limits.max_intervening_messages() {
                    return Err(HarnessError::TooManyInterveningMessages);
                }
                intervening_messages += 1;
                continue;
            }
            return Err(HarnessError::UnexpectedMessage);
        }
    }

    /// Closes child stdin, then waits for normal child termination.
    ///
    /// # Errors
    ///
    /// Returns an error for timeout or process I/O failure. On timeout, the
    /// child is killed before the error is returned.
    pub async fn close_and_wait(mut self) -> Result<std::process::ExitStatus, HarnessError> {
        drop(self.stdin);
        if let Ok(result) = timeout(self.limits.io_timeout(), self.child.wait()).await {
            result.map_err(HarnessError::ProcessIo)
        } else {
            let _ = self.child.kill().await;
            Err(HarnessError::Timeout("child exit"))
        }
    }

    /// Kills the child and waits for it to exit.
    ///
    /// # Errors
    ///
    /// Returns an error when kill or wait fails or times out.
    pub async fn kill(mut self) -> Result<std::process::ExitStatus, HarnessError> {
        self.child.kill().await?;
        timeout(self.limits.io_timeout(), self.child.wait())
            .await
            .map_err(|_| HarnessError::Timeout("child exit"))?
            .map_err(HarnessError::ProcessIo)
    }
}

fn is_notification(message: &Value) -> bool {
    message.get("jsonrpc").and_then(Value::as_str) == Some("2.0")
        && message.get("method").is_some_and(Value::is_string)
        && message.get("id").is_none()
}

async fn read_bounded_line<R>(
    reader: &mut R,
    limit: ByteLimit,
) -> Result<Option<Vec<u8>>, HarnessError>
where
    R: AsyncBufRead + Unpin,
{
    let mut line = Vec::new();
    loop {
        let available = reader.fill_buf().await?;
        if available.is_empty() {
            return if line.is_empty() {
                Ok(None)
            } else {
                trim_line_ending(&mut line);
                Ok(Some(line))
            };
        }
        let newline = available.iter().position(|byte| *byte == b'\n');
        let consumed = newline.map_or(available.len(), |position| position + 1);
        if append_bounded(&mut line, &available[..consumed], limit).is_err() {
            return Err(HarnessError::FrameTooLarge);
        }
        reader.consume(consumed);
        if newline.is_some() {
            trim_line_ending(&mut line);
            return Ok(Some(line));
        }
    }
}

fn trim_line_ending(line: &mut Vec<u8>) {
    if line.last() == Some(&b'\n') {
        line.pop();
    }
    if line.last() == Some(&b'\r') {
        line.pop();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tokio::io::BufReader;

    #[test]
    fn default_limits_are_finite() {
        let limits = HarnessLimits::default();
        assert_eq!(limits.max_frame_bytes().get(), DEFAULT_MAX_FRAME_BYTES);
        assert_eq!(
            limits.max_intervening_messages(),
            DEFAULT_MAX_INTERVENING_MESSAGES
        );
        assert_eq!(limits.io_timeout(), DEFAULT_IO_TIMEOUT);
    }

    #[tokio::test]
    async fn bounded_reader_accepts_exact_limit_and_trims_newline() {
        let mut input = BufReader::new(&b"1234\n"[..]);
        let line = read_bounded_line(&mut input, ByteLimit::new(5))
            .await
            .expect("line at exact encoded bound must pass")
            .expect("line must be present");
        assert_eq!(line, b"1234");
    }

    #[tokio::test]
    async fn bounded_reader_rejects_oversized_line() {
        let mut input = BufReader::new(&b"12345\n"[..]);
        let error = read_bounded_line(&mut input, ByteLimit::new(5))
            .await
            .expect_err("oversized encoded line must fail");
        assert!(matches!(error, HarnessError::FrameTooLarge));
    }

    #[test]
    fn notification_detection_is_strict() {
        assert!(is_notification(&json!({
            "jsonrpc": "2.0",
            "method": "notifications/progress",
            "params": {}
        })));
        assert!(!is_notification(&json!({
            "jsonrpc": "2.0",
            "id": 7,
            "method": "sampling/createMessage"
        })));
        assert!(!is_notification(&json!({"method": "notice"})));
    }

    #[test]
    fn harness_errors_do_not_echo_protocol_payloads() {
        let display = HarnessError::NonJsonStdout.to_string();
        assert_eq!(display, "MCP stdout contained a non-JSON frame");
    }
    #[test]
    fn portable_stdout_helper_catches_log_lines() {
        assert!(assert_json_only_stdout(b"{\"jsonrpc\":\"2.0\"}\n").is_ok());
        assert!(matches!(
            assert_json_only_stdout(b"starting server\n"),
            Err(TestkitError::StdoutPollution(0))
        ));
    }
}
