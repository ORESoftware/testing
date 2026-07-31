use std::{collections::BTreeMap, fmt};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use thiserror::Error;
use uuid::Uuid;

pub const PROTOCOL_VERSION: &str = "v1";
pub const EVENT_KINDS: &[&str] = &[
    "agent_registered",
    "heartbeat",
    "goal_declared",
    "task_created",
    "task_started",
    "progress_updated",
    "reflection_recorded",
    "lesson_learned",
    "error_observed",
    "task_completed",
    "agent_status_changed",
];

pub const UDP_EVENT_KINDS: &[&str] = &[
    "heartbeat",
    "progress_updated",
    "reflection_recorded",
    "error_observed",
    "agent_status_changed",
];

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Transport {
    Http,
    #[serde(rename = "websocket")]
    WebSocket,
    Tcp,
    Udp,
}

impl Transport {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Http => "http",
            Self::WebSocket => "websocket",
            Self::Tcp => "tcp",
            Self::Udp => "udp",
        }
    }
}

impl fmt::Display for Transport {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.as_str())
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct EventEnvelope {
    pub protocol_version: String,
    pub event_id: Uuid,
    pub occurred_at: DateTime<Utc>,
    pub agent: AgentRef,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub correlation_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sequence: Option<u64>,
    #[serde(flatten)]
    pub event: AgentEvent,
}

impl EventEnvelope {
    pub fn new(agent: AgentRef, event: AgentEvent) -> Self {
        Self {
            protocol_version: PROTOCOL_VERSION.to_owned(),
            event_id: Uuid::new_v4(),
            occurred_at: Utc::now(),
            agent,
            session_id: None,
            correlation_id: None,
            sequence: None,
            event,
        }
    }

    pub fn kind(&self) -> &'static str {
        self.event.kind()
    }

    pub fn task_id(&self) -> Option<&str> {
        self.event.task_id()
    }

    pub fn validate(&self) -> Result<(), ValidationError> {
        if self.protocol_version != PROTOCOL_VERSION {
            return Err(ValidationError::UnsupportedProtocol {
                received: self.protocol_version.clone(),
            });
        }
        if self.event_id.is_nil() {
            return Err(ValidationError::NilEventId);
        }
        validate_text("agent.agent_id", &self.agent.agent_id, 256)?;
        validate_text("agent.provider", &self.agent.provider, 128)?;
        validate_text("agent.model", &self.agent.model, 256)?;
        validate_optional_text("agent.instance_id", self.agent.instance_id.as_deref(), 256)?;
        validate_optional_text("session_id", self.session_id.as_deref(), 256)?;
        validate_optional_text("correlation_id", self.correlation_id.as_deref(), 256)?;
        self.event.validate()
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AgentRef {
    pub agent_id: String,
    pub provider: String,
    pub model: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub instance_id: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "data", rename_all = "snake_case")]
pub enum AgentEvent {
    AgentRegistered(AgentRegistration),
    Heartbeat(Heartbeat),
    GoalDeclared(Goal),
    TaskCreated(TaskSpec),
    TaskStarted(TaskStarted),
    ProgressUpdated(ProgressUpdate),
    ReflectionRecorded(Reflection),
    LessonLearned(Lesson),
    ErrorObserved(ErrorObservation),
    TaskCompleted(TaskCompleted),
    AgentStatusChanged(StatusChange),
}

impl AgentEvent {
    pub const fn kind(&self) -> &'static str {
        match self {
            Self::AgentRegistered(_) => "agent_registered",
            Self::Heartbeat(_) => "heartbeat",
            Self::GoalDeclared(_) => "goal_declared",
            Self::TaskCreated(_) => "task_created",
            Self::TaskStarted(_) => "task_started",
            Self::ProgressUpdated(_) => "progress_updated",
            Self::ReflectionRecorded(_) => "reflection_recorded",
            Self::LessonLearned(_) => "lesson_learned",
            Self::ErrorObserved(_) => "error_observed",
            Self::TaskCompleted(_) => "task_completed",
            Self::AgentStatusChanged(_) => "agent_status_changed",
        }
    }

    pub fn task_id(&self) -> Option<&str> {
        match self {
            Self::TaskCreated(value) => Some(&value.task_id),
            Self::TaskStarted(value) => Some(&value.task_id),
            Self::ProgressUpdated(value) => Some(&value.task_id),
            Self::ReflectionRecorded(value) => value.task_id.as_deref(),
            Self::LessonLearned(value) => value.source_task_id.as_deref(),
            Self::ErrorObserved(value) => value.task_id.as_deref(),
            Self::TaskCompleted(value) => Some(&value.task_id),
            Self::AgentRegistered(_)
            | Self::Heartbeat(_)
            | Self::GoalDeclared(_)
            | Self::AgentStatusChanged(_) => None,
        }
    }

    /// UDP is intentionally limited to low-authority telemetry. Goal/task definitions,
    /// completion claims, registrations, and learned heuristics require a reliable transport.
    pub const fn allowed_over_udp(&self) -> bool {
        matches!(
            self,
            Self::Heartbeat(_)
                | Self::ProgressUpdated(_)
                | Self::ReflectionRecorded(_)
                | Self::ErrorObserved(_)
                | Self::AgentStatusChanged(_)
        )
    }

    fn validate(&self) -> Result<(), ValidationError> {
        match self {
            Self::AgentRegistered(value) => {
                validate_text("display_name", &value.display_name, 256)?;
                validate_string_list("capabilities", &value.capabilities, 128, 256)?;
                validate_metadata(&value.metadata)
            }
            Self::Heartbeat(value) => {
                validate_optional_text("active_task_id", value.active_task_id.as_deref(), 256)?;
                validate_optional_unit_interval("load", value.load)
            }
            Self::GoalDeclared(value) => value.validate(),
            Self::TaskCreated(value) => value.validate(),
            Self::TaskStarted(value) => {
                validate_text("task_id", &value.task_id, 256)?;
                if value.attempt == 0 {
                    return Err(ValidationError::InvalidAttempt);
                }
                validate_optional_text("plan_summary", value.plan_summary.as_deref(), 8_192)
            }
            Self::ProgressUpdated(value) => value.validate(),
            Self::ReflectionRecorded(value) => value.validate(),
            Self::LessonLearned(value) => value.validate(),
            Self::ErrorObserved(value) => value.validate(),
            Self::TaskCompleted(value) => value.validate(),
            Self::AgentStatusChanged(value) => {
                validate_optional_text("reason", value.reason.as_deref(), 4_096)
            }
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AgentRegistration {
    pub display_name: String,
    #[serde(default)]
    pub capabilities: Vec<String>,
    #[serde(default)]
    pub metadata: BTreeMap<String, String>,
    #[serde(default)]
    pub status: AgentStatus,
}

#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AgentStatus {
    #[default]
    Idle,
    Planning,
    Running,
    Waiting,
    Blocked,
    Completed,
    Failed,
    Offline,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Heartbeat {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<AgentStatus>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub active_task_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub load: Option<f32>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Goal {
    pub goal_id: String,
    pub title: String,
    pub success_criteria: Vec<String>,
    #[serde(default)]
    pub constraints: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub parent_goal_id: Option<String>,
}

impl Goal {
    fn validate(&self) -> Result<(), ValidationError> {
        validate_text("goal_id", &self.goal_id, 256)?;
        validate_text("goal.title", &self.title, 2_048)?;
        validate_string_list("success_criteria", &self.success_criteria, 64, 2_048)?;
        validate_string_list("constraints", &self.constraints, 64, 2_048)?;
        validate_optional_text("parent_goal_id", self.parent_goal_id.as_deref(), 256)
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TaskSpec {
    pub task_id: String,
    pub title: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub goal_id: Option<String>,
    #[serde(default)]
    pub depends_on: Vec<String>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expected_outcome: Option<String>,
}

impl TaskSpec {
    fn validate(&self) -> Result<(), ValidationError> {
        validate_text("task_id", &self.task_id, 256)?;
        validate_text("task.title", &self.title, 2_048)?;
        validate_optional_text("goal_id", self.goal_id.as_deref(), 256)?;
        validate_string_list("depends_on", &self.depends_on, 128, 256)?;
        validate_string_list("tags", &self.tags, 128, 256)?;
        validate_optional_text("expected_outcome", self.expected_outcome.as_deref(), 8_192)
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TaskStarted {
    pub task_id: String,
    pub attempt: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub plan_summary: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ProgressUpdate {
    pub task_id: String,
    pub progress: f32,
    pub summary: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub blocker: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_action: Option<String>,
}

impl ProgressUpdate {
    fn validate(&self) -> Result<(), ValidationError> {
        validate_text("task_id", &self.task_id, 256)?;
        validate_unit_interval("progress", self.progress)?;
        validate_text("progress.summary", &self.summary, 16_384)?;
        validate_optional_text("blocker", self.blocker.as_deref(), 8_192)?;
        validate_optional_text("next_action", self.next_action.as_deref(), 8_192)
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Reflection {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub task_id: Option<String>,
    pub summary: String,
    pub confidence: f32,
    #[serde(default)]
    pub assumptions: Vec<String>,
    #[serde(default)]
    pub evidence: Vec<EvidenceReference>,
    #[serde(default)]
    pub alternatives_considered: Vec<String>,
    #[serde(default)]
    pub risks: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next_action: Option<String>,
}

impl Reflection {
    fn validate(&self) -> Result<(), ValidationError> {
        validate_optional_text("task_id", self.task_id.as_deref(), 256)?;
        validate_text("reflection.summary", &self.summary, 16_384)?;
        validate_unit_interval("confidence", self.confidence)?;
        validate_string_list("assumptions", &self.assumptions, 64, 4_096)?;
        if self.evidence.len() > 128 {
            return Err(ValidationError::TooManyValues {
                field: "evidence",
                maximum: 128,
            });
        }
        for reference in &self.evidence {
            reference.validate()?;
        }
        validate_string_list(
            "alternatives_considered",
            &self.alternatives_considered,
            64,
            4_096,
        )?;
        validate_string_list("risks", &self.risks, 64, 4_096)?;
        validate_optional_text("next_action", self.next_action.as_deref(), 8_192)
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EvidenceReference {
    pub kind: String,
    pub reference: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub summary: Option<String>,
}

impl EvidenceReference {
    fn validate(&self) -> Result<(), ValidationError> {
        validate_text("evidence.kind", &self.kind, 128)?;
        validate_text("evidence.reference", &self.reference, 4_096)?;
        validate_optional_text("evidence.summary", self.summary.as_deref(), 4_096)
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Lesson {
    pub lesson_id: String,
    pub statement: String,
    pub confidence: f32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source_task_id: Option<String>,
    #[serde(default)]
    pub evidence: Vec<EvidenceReference>,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub applicability: Option<String>,
}

impl Lesson {
    fn validate(&self) -> Result<(), ValidationError> {
        validate_text("lesson_id", &self.lesson_id, 256)?;
        validate_text("lesson.statement", &self.statement, 16_384)?;
        validate_unit_interval("lesson.confidence", self.confidence)?;
        validate_optional_text("source_task_id", self.source_task_id.as_deref(), 256)?;
        if self.evidence.len() > 128 {
            return Err(ValidationError::TooManyValues {
                field: "lesson.evidence",
                maximum: 128,
            });
        }
        for reference in &self.evidence {
            reference.validate()?;
        }
        validate_string_list("lesson.tags", &self.tags, 128, 256)?;
        validate_optional_text("lesson.applicability", self.applicability.as_deref(), 8_192)
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ErrorObservation {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub task_id: Option<String>,
    pub code: String,
    pub message: String,
    #[serde(default)]
    pub recoverable: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub proposed_recovery: Option<String>,
}

impl ErrorObservation {
    fn validate(&self) -> Result<(), ValidationError> {
        validate_optional_text("task_id", self.task_id.as_deref(), 256)?;
        validate_text("error.code", &self.code, 256)?;
        validate_text("error.message", &self.message, 16_384)?;
        validate_optional_text(
            "proposed_recovery",
            self.proposed_recovery.as_deref(),
            8_192,
        )
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TaskCompleted {
    pub task_id: String,
    pub outcome: TaskOutcome,
    pub summary: String,
    #[serde(default)]
    pub artifacts: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub actual_result: Option<String>,
}

impl TaskCompleted {
    fn validate(&self) -> Result<(), ValidationError> {
        validate_text("task_id", &self.task_id, 256)?;
        validate_text("completion.summary", &self.summary, 16_384)?;
        validate_string_list("artifacts", &self.artifacts, 256, 4_096)?;
        validate_optional_text("actual_result", self.actual_result.as_deref(), 16_384)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskOutcome {
    Succeeded,
    Failed,
    Canceled,
    Partial,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StatusChange {
    pub status: AgentStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct TransportFrame {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    pub event: EventEnvelope,
}

impl fmt::Debug for TransportFrame {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("TransportFrame")
            .field("token_configured", &self.token.is_some())
            .field("event", &self.event)
            .finish()
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(untagged)]
pub enum TransportPayload {
    Frame(TransportFrame),
    Event(EventEnvelope),
}

impl fmt::Debug for TransportPayload {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Frame(frame) => formatter.debug_tuple("Frame").field(frame).finish(),
            Self::Event(event) => formatter.debug_tuple("Event").field(event).finish(),
        }
    }
}

impl TransportPayload {
    pub fn into_parts(self) -> (Option<String>, EventEnvelope) {
        match self {
            Self::Frame(frame) => (frame.token, frame.event),
            Self::Event(event) => (None, event),
        }
    }
}

#[derive(Clone, Debug, Error, PartialEq)]
pub enum ValidationError {
    #[error("unsupported protocol version: {received}")]
    UnsupportedProtocol { received: String },
    #[error("event_id must not be nil")]
    NilEventId,
    #[error("{field} must not be empty")]
    EmptyText { field: &'static str },
    #[error("{field} exceeds its {maximum}-byte limit")]
    TextTooLong { field: &'static str, maximum: usize },
    #[error("{field} contains more than {maximum} values")]
    TooManyValues { field: &'static str, maximum: usize },
    #[error("{field} must be a finite number between 0 and 1")]
    InvalidUnitInterval { field: &'static str },
    #[error("task attempts start at 1")]
    InvalidAttempt,
}

fn validate_text(field: &'static str, value: &str, maximum: usize) -> Result<(), ValidationError> {
    if value.trim().is_empty() {
        return Err(ValidationError::EmptyText { field });
    }
    if value.len() > maximum {
        return Err(ValidationError::TextTooLong { field, maximum });
    }
    Ok(())
}

fn validate_optional_text(
    field: &'static str,
    value: Option<&str>,
    maximum: usize,
) -> Result<(), ValidationError> {
    if let Some(value) = value {
        validate_text(field, value, maximum)?;
    }
    Ok(())
}

fn validate_metadata(values: &BTreeMap<String, String>) -> Result<(), ValidationError> {
    if values.len() > 128 {
        return Err(ValidationError::TooManyValues {
            field: "metadata",
            maximum: 128,
        });
    }
    for (key, value) in values {
        validate_text("metadata.key", key, 128)?;
        validate_text("metadata.value", value, 2_048)?;
    }
    Ok(())
}

fn validate_string_list(
    field: &'static str,
    values: &[String],
    maximum_values: usize,
    maximum_length: usize,
) -> Result<(), ValidationError> {
    if values.len() > maximum_values {
        return Err(ValidationError::TooManyValues {
            field,
            maximum: maximum_values,
        });
    }
    for value in values {
        validate_text(field, value, maximum_length)?;
    }
    Ok(())
}

fn validate_unit_interval(field: &'static str, value: f32) -> Result<(), ValidationError> {
    if value.is_finite() && (0.0..=1.0).contains(&value) {
        Ok(())
    } else {
        Err(ValidationError::InvalidUnitInterval { field })
    }
}

fn validate_optional_unit_interval(
    field: &'static str,
    value: Option<f32>,
) -> Result<(), ValidationError> {
    if let Some(value) = value {
        validate_unit_interval(field, value)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn agent() -> AgentRef {
        AgentRef {
            agent_id: "agent-1".to_owned(),
            provider: "openai".to_owned(),
            model: "gpt".to_owned(),
            instance_id: None,
        }
    }

    #[test]
    fn validates_confidence() {
        let envelope = EventEnvelope::new(
            agent(),
            AgentEvent::ReflectionRecorded(Reflection {
                task_id: None,
                summary: "Checked the outcome against the success criteria.".to_owned(),
                confidence: 1.2,
                assumptions: Vec::new(),
                evidence: Vec::new(),
                alternatives_considered: Vec::new(),
                risks: Vec::new(),
                next_action: None,
            }),
        );

        assert!(matches!(
            envelope.validate(),
            Err(ValidationError::InvalidUnitInterval {
                field: "confidence"
            })
        ));
    }

    #[test]
    fn event_kinds_remain_protocol_stable() {
        assert_eq!(EVENT_KINDS.len(), 11);
        assert!(EVENT_KINDS.contains(&"reflection_recorded"));
        assert!(EVENT_KINDS.contains(&"lesson_learned"));
    }

    #[test]
    fn udp_policy_only_allows_low_authority_telemetry() {
        let heartbeat = AgentEvent::Heartbeat(Heartbeat {
            status: Some(AgentStatus::Running),
            active_task_id: Some("task-1".to_owned()),
            load: Some(0.4),
        });
        let lesson = AgentEvent::LessonLearned(Lesson {
            lesson_id: "lesson-1".to_owned(),
            statement: "Validate conclusions against evidence.".to_owned(),
            confidence: 0.8,
            source_task_id: None,
            evidence: Vec::new(),
            tags: Vec::new(),
            applicability: None,
        });

        assert_eq!(UDP_EVENT_KINDS.len(), 5);
        assert!(heartbeat.allowed_over_udp());
        assert!(!lesson.allowed_over_udp());
    }

    #[test]
    fn transport_debug_output_redacts_tokens() {
        let frame = TransportFrame {
            token: Some("never-log-this-authentication-token".to_owned()),
            event: EventEnvelope::new(
                agent(),
                AgentEvent::Heartbeat(Heartbeat {
                    status: Some(AgentStatus::Idle),
                    active_task_id: None,
                    load: Some(0.1),
                }),
            ),
        };

        let debug = format!("{frame:?}");
        assert!(debug.contains("token_configured: true"));
        assert!(!debug.contains("never-log-this-authentication-token"));
    }
}
