use std::{collections::BTreeMap, fmt, hash::Hash, num::NonZeroUsize, sync::Arc};

use chrono::{DateTime, Utc};
use lru::LruCache;
use serde::{Deserialize, Serialize};
use thiserror::Error;
use tokio::sync::{RwLock, broadcast};
use uuid::Uuid;

use crate::{
    config::CacheConfig,
    model::{
        AgentEvent, AgentRef, AgentStatus, ErrorObservation, EventEnvelope, Goal, Lesson,
        Reflection, TaskOutcome, TaskSpec, Transport, ValidationError,
    },
};

const SNAPSHOT_EVENT_LIMIT: usize = 250;

#[derive(Clone)]
pub struct Store {
    state: Arc<StoreState>,
}

impl fmt::Debug for Store {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.debug_struct("Store").finish_non_exhaustive()
    }
}

#[derive(Debug)]
struct StoreState {
    inner: RwLock<StoreInner>,
    updates: broadcast::Sender<StoreUpdate>,
}

#[derive(Debug)]
struct StoreInner {
    agents: LruCache<String, AgentState>,
    goals: LruCache<ScopedId, GoalState>,
    tasks: LruCache<ScopedId, TaskState>,
    lessons: LruCache<ScopedId, LessonState>,
    events: LruCache<Uuid, EventRecord>,
    seen_event_ids: LruCache<Uuid, u64>,
    evictions: EvictionCounters,
    counters: Counters,
    revision: u64,
    started_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Eq, Hash, PartialEq)]
struct ScopedId {
    agent_id: String,
    entity_id: String,
}

impl ScopedId {
    fn new(agent_id: &str, entity_id: &str) -> Self {
        Self {
            agent_id: agent_id.to_owned(),
            entity_id: entity_id.to_owned(),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AgentState {
    pub agent: AgentRef,
    pub display_name: String,
    pub capabilities: Vec<String>,
    pub metadata: BTreeMap<String, String>,
    pub status: AgentStatus,
    pub session_id: Option<String>,
    pub current_goal_id: Option<String>,
    pub active_task_id: Option<String>,
    pub registered_at: DateTime<Utc>,
    pub last_seen_at: DateTime<Utc>,
    pub completed_tasks: u64,
    pub failed_tasks: u64,
    pub latest_reflection: Option<Reflection>,
    pub latest_error: Option<ErrorObservation>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct GoalState {
    pub goal: Goal,
    pub agent_id: String,
    pub declared_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TaskState {
    pub task: TaskSpec,
    pub agent_id: String,
    pub status: TaskStatus,
    pub progress: f32,
    pub attempt: u32,
    pub plan_summary: Option<String>,
    pub progress_summary: Option<String>,
    pub blocker: Option<String>,
    pub next_action: Option<String>,
    pub latest_reflection: Option<Reflection>,
    pub outcome: Option<TaskOutcome>,
    pub completion_summary: Option<String>,
    pub artifacts: Vec<String>,
    pub inferred_from_out_of_order_event: bool,
    pub definition_updated_at: DateTime<Utc>,
    pub created_at: DateTime<Utc>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub updated_at: DateTime<Utc>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    Pending,
    Running,
    Blocked,
    Succeeded,
    Failed,
    Canceled,
    Partial,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LessonState {
    pub lesson: Lesson,
    pub agent_id: String,
    pub learned_at: DateTime<Utc>,
    pub observations: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct EventRecord {
    pub event: EventEnvelope,
    pub transport: Transport,
    pub received_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct StoreUpdate {
    pub revision: u64,
    pub event_id: Uuid,
    pub kind: String,
    pub agent_id: String,
    pub task_id: Option<String>,
    pub transport: Transport,
    pub received_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct IngestAck {
    pub accepted: bool,
    pub duplicate: bool,
    pub event_id: Uuid,
    pub revision: u64,
    pub transport: Transport,
    pub received_at: DateTime<Utc>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Snapshot {
    pub generated_at: DateTime<Utc>,
    pub started_at: DateTime<Utc>,
    pub revision: u64,
    pub agents: Vec<AgentState>,
    pub goals: Vec<GoalState>,
    pub tasks: Vec<TaskState>,
    pub lessons: Vec<LessonState>,
    pub recent_events: Vec<EventRecord>,
    pub caches: CacheSnapshot,
    pub counters: Counters,
}

#[derive(Clone, Debug, Serialize)]
pub struct MetricsSnapshot {
    pub revision: u64,
    pub caches: CacheSnapshot,
    pub counters: Counters,
    pub projection: ProjectionCounts,
}

#[derive(Clone, Copy, Debug, Serialize)]
pub struct ProjectionCounts {
    pub agents: usize,
    pub goals: usize,
    pub tasks: usize,
    pub lessons: usize,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct CacheSnapshot {
    pub agents: CacheStats,
    pub goals: CacheStats,
    pub tasks: CacheStats,
    pub lessons: CacheStats,
    pub events: CacheStats,
    pub idempotency: CacheStats,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize)]
pub struct CacheStats {
    pub length: usize,
    pub capacity: usize,
    pub evictions: u64,
    pub pressure: f64,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Counters {
    pub accepted: u64,
    pub duplicate: u64,
    pub rejected: u64,
    pub accepted_by_transport: BTreeMap<String, u64>,
    pub rejected_by_transport: BTreeMap<String, u64>,
}

#[derive(Clone, Copy, Debug, Default)]
struct EvictionCounters {
    agents: u64,
    goals: u64,
    tasks: u64,
    lessons: u64,
    events: u64,
    idempotency: u64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum CompletionBucket {
    Completed,
    Failed,
}

#[derive(Debug, Error)]
pub enum StoreError {
    #[error(transparent)]
    Validation(#[from] ValidationError),
}

impl Store {
    pub fn new(cache: CacheConfig, update_channel_capacity: usize) -> Self {
        let (updates, _) = broadcast::channel(update_channel_capacity);
        let inner = StoreInner {
            agents: new_cache(cache.agents),
            goals: new_cache(cache.goals),
            tasks: new_cache(cache.tasks),
            lessons: new_cache(cache.lessons),
            events: new_cache(cache.events),
            seen_event_ids: new_cache(cache.idempotency),
            evictions: EvictionCounters::default(),
            counters: Counters::default(),
            revision: 0,
            started_at: Utc::now(),
        };
        Self {
            state: Arc::new(StoreState {
                inner: RwLock::new(inner),
                updates,
            }),
        }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<StoreUpdate> {
        self.state.updates.subscribe()
    }

    pub async fn ingest(
        &self,
        event: EventEnvelope,
        transport: Transport,
    ) -> Result<IngestAck, StoreError> {
        if let Err(error) = event.validate() {
            self.record_rejection(transport).await;
            return Err(StoreError::Validation(error));
        }

        let received_at = Utc::now();
        let mut inner = self.state.inner.write().await;

        if inner.seen_event_ids.get(&event.event_id).is_some() {
            inner.counters.duplicate = inner.counters.duplicate.saturating_add(1);
            return Ok(IngestAck {
                accepted: true,
                duplicate: true,
                event_id: event.event_id,
                revision: inner.revision,
                transport,
                received_at,
            });
        }

        apply_event(&mut inner, &event);
        inner.revision = inner.revision.saturating_add(1);
        inner.counters.accepted = inner.counters.accepted.saturating_add(1);
        *inner
            .counters
            .accepted_by_transport
            .entry(transport.to_string())
            .or_default() += 1;

        let revision = inner.revision;
        if push_with_eviction(&mut inner.seen_event_ids, event.event_id, revision).is_some() {
            inner.evictions.idempotency = inner.evictions.idempotency.saturating_add(1);
        }
        let update = StoreUpdate {
            revision,
            event_id: event.event_id,
            kind: event.kind().to_owned(),
            agent_id: event.agent.agent_id.clone(),
            task_id: event.task_id().map(str::to_owned),
            transport,
            received_at,
        };
        let record = EventRecord {
            event: event.clone(),
            transport,
            received_at,
        };
        let event_id = event.event_id;
        if push_with_eviction(&mut inner.events, event_id, record).is_some() {
            inner.evictions.events = inner.evictions.events.saturating_add(1);
        }
        drop(inner);

        let _ = self.state.updates.send(update);
        Ok(IngestAck {
            accepted: true,
            duplicate: false,
            event_id,
            revision,
            transport,
            received_at,
        })
    }

    pub async fn record_rejection(&self, transport: Transport) {
        let mut inner = self.state.inner.write().await;
        inner.counters.rejected = inner.counters.rejected.saturating_add(1);
        *inner
            .counters
            .rejected_by_transport
            .entry(transport.to_string())
            .or_default() += 1;
    }

    pub async fn snapshot(&self) -> Snapshot {
        let inner = self.state.inner.read().await;
        Snapshot {
            generated_at: Utc::now(),
            started_at: inner.started_at,
            revision: inner.revision,
            agents: inner
                .agents
                .iter()
                .map(|(_, value)| value)
                .cloned()
                .collect(),
            goals: inner
                .goals
                .iter()
                .map(|(_, value)| value)
                .cloned()
                .collect(),
            tasks: inner
                .tasks
                .iter()
                .map(|(_, value)| value)
                .cloned()
                .collect(),
            lessons: inner
                .lessons
                .iter()
                .map(|(_, value)| value)
                .cloned()
                .collect(),
            recent_events: inner
                .events
                .iter()
                .take(SNAPSHOT_EVENT_LIMIT)
                .map(|(_, value)| value)
                .cloned()
                .collect(),
            caches: cache_snapshot(&inner),
            counters: inner.counters.clone(),
        }
    }

    pub async fn revision(&self) -> u64 {
        self.state.inner.read().await.revision
    }

    pub async fn metrics_snapshot(&self) -> MetricsSnapshot {
        let inner = self.state.inner.read().await;
        MetricsSnapshot {
            revision: inner.revision,
            caches: cache_snapshot(&inner),
            counters: inner.counters.clone(),
            projection: ProjectionCounts {
                agents: inner.agents.len(),
                goals: inner.goals.len(),
                tasks: inner.tasks.len(),
                lessons: inner.lessons.len(),
            },
        }
    }
}

fn new_cache<K: Hash + Eq, V>(capacity: usize) -> LruCache<K, V> {
    LruCache::new(NonZeroUsize::new(capacity).expect("validated cache capacity"))
}

fn cache_stats<K, V>(cache: &LruCache<K, V>, evictions: u64) -> CacheStats {
    let capacity = cache.cap().get();
    CacheStats {
        length: cache.len(),
        capacity,
        evictions,
        pressure: cache.len() as f64 / capacity as f64,
    }
}

fn cache_snapshot(inner: &StoreInner) -> CacheSnapshot {
    CacheSnapshot {
        agents: cache_stats(&inner.agents, inner.evictions.agents),
        goals: cache_stats(&inner.goals, inner.evictions.goals),
        tasks: cache_stats(&inner.tasks, inner.evictions.tasks),
        lessons: cache_stats(&inner.lessons, inner.evictions.lessons),
        events: cache_stats(&inner.events, inner.evictions.events),
        idempotency: cache_stats(&inner.seen_event_ids, inner.evictions.idempotency),
    }
}

fn push_with_eviction<K, V>(cache: &mut LruCache<K, V>, key: K, value: V) -> Option<V>
where
    K: Hash + Eq,
{
    let existed = cache.peek(&key).is_some();
    let displaced = cache.push(key, value);
    if existed {
        None
    } else {
        displaced.map(|(_, value)| value)
    }
}

fn apply_event(inner: &mut StoreInner, envelope: &EventEnvelope) {
    let agent_id = envelope.agent.agent_id.clone();
    ensure_agent(inner, envelope);

    if let Some(agent) = inner.agents.get_mut(&agent_id) {
        agent.agent = envelope.agent.clone();
        agent.last_seen_at = agent.last_seen_at.max(envelope.occurred_at);
        if envelope.session_id.is_some() {
            agent.session_id.clone_from(&envelope.session_id);
        }
    }

    match &envelope.event {
        AgentEvent::AgentRegistered(registration) => {
            if let Some(agent) = inner.agents.get_mut(&agent_id) {
                agent.display_name.clone_from(&registration.display_name);
                agent.capabilities.clone_from(&registration.capabilities);
                agent.metadata.clone_from(&registration.metadata);
                if matches!(agent.status, AgentStatus::Idle) {
                    agent.status = registration.status.clone();
                }
                agent.registered_at = agent.registered_at.min(envelope.occurred_at);
            }
        }
        AgentEvent::Heartbeat(heartbeat) => {
            if let Some(agent) = inner.agents.get_mut(&agent_id) {
                if let Some(status) = &heartbeat.status {
                    agent.status = status.clone();
                }
                if heartbeat.active_task_id.is_some() {
                    agent.active_task_id.clone_from(&heartbeat.active_task_id);
                }
            }
        }
        AgentEvent::GoalDeclared(goal) => {
            let goal_id = goal.goal_id.clone();
            let goal_key = ScopedId::new(&agent_id, &goal_id);
            let goal_state = GoalState {
                goal: goal.clone(),
                agent_id: agent_id.clone(),
                declared_at: envelope.occurred_at,
            };
            if push_with_eviction(&mut inner.goals, goal_key, goal_state).is_some() {
                inner.evictions.goals = inner.evictions.goals.saturating_add(1);
            }
            if let Some(agent) = inner.agents.get_mut(&agent_id) {
                agent.current_goal_id = Some(goal_id);
                if matches!(agent.status, AgentStatus::Idle | AgentStatus::Planning) {
                    agent.status = AgentStatus::Planning;
                }
            }
        }
        AgentEvent::TaskCreated(task) => {
            let task_key = ScopedId::new(&agent_id, &task.task_id);
            if let Some(existing) = inner.tasks.get_mut(&task_key) {
                let definition_is_authoritative = existing.inferred_from_out_of_order_event
                    || envelope.occurred_at >= existing.definition_updated_at;
                if definition_is_authoritative {
                    existing.task = task.clone();
                    existing.agent_id.clone_from(&agent_id);
                    existing.inferred_from_out_of_order_event = false;
                    existing.definition_updated_at = envelope.occurred_at;
                    existing.created_at = existing.created_at.min(envelope.occurred_at);
                    existing.updated_at = existing.updated_at.max(envelope.occurred_at);
                }
            } else {
                let task_state = TaskState::from_spec(
                    task.clone(),
                    agent_id.clone(),
                    envelope.occurred_at,
                    false,
                );
                insert_task(inner, task_key, task_state);
            }
        }
        AgentEvent::TaskStarted(started) => {
            ensure_task(inner, &started.task_id, &agent_id, envelope.occurred_at);
            let task_key = ScopedId::new(&agent_id, &started.task_id);
            let mut previous_outcome = None;
            let mut applied = false;
            if let Some(task) = inner.tasks.get_mut(&task_key)
                && envelope.occurred_at >= task.updated_at
            {
                previous_outcome = task.outcome.take();
                task.status = TaskStatus::Running;
                task.attempt = started.attempt;
                task.plan_summary.clone_from(&started.plan_summary);
                task.blocker = None;
                task.completion_summary = None;
                task.artifacts.clear();
                task.completed_at = None;
                task.started_at = Some(envelope.occurred_at);
                task.updated_at = envelope.occurred_at;
                applied = true;
            }
            if applied {
                if let Some(agent) = inner.agents.get_mut(&agent_id) {
                    remove_completion_counter(agent, previous_outcome);
                    agent.status = AgentStatus::Running;
                    agent.active_task_id = Some(started.task_id.clone());
                }
            }
        }
        AgentEvent::ProgressUpdated(progress) => {
            ensure_task(inner, &progress.task_id, &agent_id, envelope.occurred_at);
            let task_key = ScopedId::new(&agent_id, &progress.task_id);
            let mut previous_outcome = None;
            let mut applied = false;
            if let Some(task) = inner.tasks.get_mut(&task_key)
                && envelope.occurred_at >= task.updated_at
            {
                previous_outcome = task.outcome.take();
                task.progress = progress.progress;
                task.progress_summary = Some(progress.summary.clone());
                task.blocker.clone_from(&progress.blocker);
                task.next_action.clone_from(&progress.next_action);
                task.status = if progress.blocker.is_some() {
                    TaskStatus::Blocked
                } else {
                    TaskStatus::Running
                };
                task.completion_summary = None;
                task.artifacts.clear();
                task.completed_at = None;
                task.updated_at = envelope.occurred_at;
                applied = true;
            }
            if applied {
                if let Some(agent) = inner.agents.get_mut(&agent_id) {
                    remove_completion_counter(agent, previous_outcome);
                    agent.status = if progress.blocker.is_some() {
                        AgentStatus::Blocked
                    } else {
                        AgentStatus::Running
                    };
                    agent.active_task_id = Some(progress.task_id.clone());
                }
            }
        }
        AgentEvent::ReflectionRecorded(reflection) => {
            if let Some(task_id) = &reflection.task_id {
                ensure_task(inner, task_id, &agent_id, envelope.occurred_at);
                let task_key = ScopedId::new(&agent_id, task_id);
                if let Some(task) = inner.tasks.get_mut(&task_key)
                    && envelope.occurred_at >= task.updated_at
                {
                    task.latest_reflection = Some(reflection.clone());
                    task.next_action.clone_from(&reflection.next_action);
                    task.updated_at = envelope.occurred_at;
                }
            }
            if let Some(agent) = inner.agents.get_mut(&agent_id) {
                agent.latest_reflection = Some(reflection.clone());
            }
        }
        AgentEvent::LessonLearned(lesson) => {
            let lesson_key = ScopedId::new(&agent_id, &lesson.lesson_id);
            let observations = inner
                .lessons
                .peek(&lesson_key)
                .map_or(1, |state| state.observations.saturating_add(1));
            let lesson_state = LessonState {
                lesson: lesson.clone(),
                agent_id: agent_id.clone(),
                learned_at: envelope.occurred_at,
                observations,
            };
            if push_with_eviction(&mut inner.lessons, lesson_key, lesson_state).is_some() {
                inner.evictions.lessons = inner.evictions.lessons.saturating_add(1);
            }
        }
        AgentEvent::ErrorObserved(error) => {
            if let Some(task_id) = &error.task_id {
                ensure_task(inner, task_id, &agent_id, envelope.occurred_at);
                let task_key = ScopedId::new(&agent_id, task_id);
                if let Some(task) = inner.tasks.get_mut(&task_key)
                    && envelope.occurred_at >= task.updated_at
                {
                    task.blocker = Some(error.message.clone());
                    task.next_action.clone_from(&error.proposed_recovery);
                    task.status = TaskStatus::Blocked;
                    task.updated_at = envelope.occurred_at;
                }
            }
            if let Some(agent) = inner.agents.get_mut(&agent_id) {
                agent.latest_error = Some(error.clone());
                agent.status = AgentStatus::Blocked;
            }
        }
        AgentEvent::TaskCompleted(completed) => {
            ensure_task(inner, &completed.task_id, &agent_id, envelope.occurred_at);
            let task_key = ScopedId::new(&agent_id, &completed.task_id);
            let task_status = match completed.outcome {
                TaskOutcome::Succeeded => TaskStatus::Succeeded,
                TaskOutcome::Failed => TaskStatus::Failed,
                TaskOutcome::Canceled => TaskStatus::Canceled,
                TaskOutcome::Partial => TaskStatus::Partial,
            };
            let mut previous_outcome = None;
            let mut applied = false;
            if let Some(task) = inner.tasks.get_mut(&task_key)
                && envelope.occurred_at >= task.updated_at
            {
                previous_outcome = task.outcome;
                task.status = task_status;
                if completed.outcome == TaskOutcome::Succeeded {
                    task.progress = 1.0;
                }
                task.outcome = Some(completed.outcome);
                task.completion_summary = Some(completed.summary.clone());
                task.artifacts.clone_from(&completed.artifacts);
                task.blocker = None;
                task.completed_at = Some(envelope.occurred_at);
                task.updated_at = envelope.occurred_at;
                applied = true;
            }
            if applied {
                if let Some(agent) = inner.agents.get_mut(&agent_id) {
                    agent.active_task_id = None;
                    reconcile_completion_counters(agent, previous_outcome, completed.outcome);
                }
            }
        }
        AgentEvent::AgentStatusChanged(change) => {
            if let Some(agent) = inner.agents.get_mut(&agent_id) {
                agent.status = change.status.clone();
            }
        }
    }
}

fn ensure_agent(inner: &mut StoreInner, envelope: &EventEnvelope) {
    let key = envelope.agent.agent_id.clone();
    if inner.agents.peek(&key).is_some() {
        return;
    }

    let (completed_tasks, failed_tasks) = task_counts(inner, &key);
    let state = AgentState {
        agent: envelope.agent.clone(),
        display_name: envelope.agent.agent_id.clone(),
        capabilities: Vec::new(),
        metadata: BTreeMap::new(),
        status: AgentStatus::Idle,
        session_id: envelope.session_id.clone(),
        current_goal_id: None,
        active_task_id: None,
        registered_at: envelope.occurred_at,
        last_seen_at: envelope.occurred_at,
        completed_tasks,
        failed_tasks,
        latest_reflection: None,
        latest_error: None,
    };
    if push_with_eviction(&mut inner.agents, key, state).is_some() {
        inner.evictions.agents = inner.evictions.agents.saturating_add(1);
    }
}

fn ensure_task(inner: &mut StoreInner, task_id: &str, agent_id: &str, occurred_at: DateTime<Utc>) {
    let key = ScopedId::new(agent_id, task_id);
    if inner.tasks.peek(&key).is_some() {
        return;
    }

    let spec = TaskSpec {
        task_id: task_id.to_owned(),
        title: format!("Task {task_id}"),
        goal_id: None,
        depends_on: Vec::new(),
        tags: vec!["inferred".to_owned()],
        expected_outcome: None,
    };
    let state = TaskState::from_spec(spec, agent_id.to_owned(), occurred_at, true);
    insert_task(inner, key, state);
}

fn insert_task(inner: &mut StoreInner, key: ScopedId, state: TaskState) {
    if let Some(evicted) = push_with_eviction(&mut inner.tasks, key, state) {
        inner.evictions.tasks = inner.evictions.tasks.saturating_add(1);
        if let Some(agent) = inner.agents.get_mut(&evicted.agent_id) {
            remove_completion_counter(agent, evicted.outcome);
        }
    }
}

fn task_counts(inner: &StoreInner, agent_id: &str) -> (u64, u64) {
    inner
        .tasks
        .iter()
        .filter(|(_, task)| task.agent_id == agent_id)
        .fold((0_u64, 0_u64), |(completed, failed), (_, task)| match task
            .outcome
            .map(completion_bucket)
        {
            Some(CompletionBucket::Completed) => (completed.saturating_add(1), failed),
            Some(CompletionBucket::Failed) => (completed, failed.saturating_add(1)),
            None => (completed, failed),
        })
}

const fn completion_bucket(outcome: TaskOutcome) -> CompletionBucket {
    match outcome {
        TaskOutcome::Succeeded | TaskOutcome::Partial => CompletionBucket::Completed,
        TaskOutcome::Failed | TaskOutcome::Canceled => CompletionBucket::Failed,
    }
}

fn remove_completion_counter(agent: &mut AgentState, outcome: Option<TaskOutcome>) {
    match outcome.map(completion_bucket) {
        Some(CompletionBucket::Completed) => {
            agent.completed_tasks = agent.completed_tasks.saturating_sub(1);
        }
        Some(CompletionBucket::Failed) => {
            agent.failed_tasks = agent.failed_tasks.saturating_sub(1);
        }
        None => {}
    }
}

fn reconcile_completion_counters(
    agent: &mut AgentState,
    previous_outcome: Option<TaskOutcome>,
    current_outcome: TaskOutcome,
) {
    let previous_bucket = previous_outcome.map(completion_bucket);
    let current_bucket = completion_bucket(current_outcome);

    if previous_bucket != Some(current_bucket) {
        remove_completion_counter(agent, previous_outcome);
        match current_bucket {
            CompletionBucket::Completed => {
                agent.completed_tasks = agent.completed_tasks.saturating_add(1);
            }
            CompletionBucket::Failed => {
                agent.failed_tasks = agent.failed_tasks.saturating_add(1);
            }
        }
    }

    agent.status = match current_bucket {
        CompletionBucket::Completed => AgentStatus::Completed,
        CompletionBucket::Failed => AgentStatus::Failed,
    };
}

impl TaskState {
    fn from_spec(
        task: TaskSpec,
        agent_id: String,
        occurred_at: DateTime<Utc>,
        inferred: bool,
    ) -> Self {
        Self {
            task,
            agent_id,
            status: TaskStatus::Pending,
            progress: 0.0,
            attempt: 0,
            plan_summary: None,
            progress_summary: None,
            blocker: None,
            next_action: None,
            latest_reflection: None,
            outcome: None,
            completion_summary: None,
            artifacts: Vec::new(),
            inferred_from_out_of_order_event: inferred,
            definition_updated_at: occurred_at,
            created_at: occurred_at,
            started_at: None,
            completed_at: None,
            updated_at: occurred_at,
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::{
        config::CacheConfig,
        model::{AgentRef, ProgressUpdate, TaskCompleted},
    };

    use super::*;

    fn event(agent_id: &str, task_id: &str, progress: f32) -> EventEnvelope {
        EventEnvelope::new(
            AgentRef {
                agent_id: agent_id.to_owned(),
                provider: "openai".to_owned(),
                model: "gpt".to_owned(),
                instance_id: None,
            },
            AgentEvent::ProgressUpdated(ProgressUpdate {
                task_id: task_id.to_owned(),
                progress,
                summary: "Made measurable progress.".to_owned(),
                blocker: None,
                next_action: Some("Continue validation.".to_owned()),
            }),
        )
    }

    fn completion_event(agent_id: &str, task_id: &str, outcome: TaskOutcome) -> EventEnvelope {
        EventEnvelope::new(
            AgentRef {
                agent_id: agent_id.to_owned(),
                provider: "openai".to_owned(),
                model: "gpt".to_owned(),
                instance_id: None,
            },
            AgentEvent::TaskCompleted(TaskCompleted {
                task_id: task_id.to_owned(),
                outcome,
                summary: "Recorded outcome.".to_owned(),
                artifacts: Vec::new(),
                actual_result: None,
            }),
        )
    }

    #[tokio::test]
    async fn applies_out_of_order_progress_and_deduplicates() {
        let store = Store::new(
            CacheConfig {
                agents: 2,
                goals: 2,
                tasks: 2,
                lessons: 2,
                events: 4,
                idempotency: 8,
            },
            8,
        );
        let event = event("agent-a", "task-a", 0.5);

        let first = store.ingest(event.clone(), Transport::Http).await.unwrap();
        let duplicate = store.ingest(event, Transport::Tcp).await.unwrap();
        let snapshot = store.snapshot().await;

        assert!(!first.duplicate);
        assert!(duplicate.duplicate);
        assert_eq!(snapshot.tasks.len(), 1);
        assert_eq!(snapshot.tasks[0].progress, 0.5);
        assert!(snapshot.tasks[0].inferred_from_out_of_order_event);
        assert_eq!(snapshot.counters.accepted, 1);
        assert_eq!(snapshot.counters.duplicate, 1);
    }

    #[tokio::test]
    async fn reports_lru_evictions() {
        let store = Store::new(
            CacheConfig {
                agents: 1,
                goals: 1,
                tasks: 1,
                lessons: 1,
                events: 1,
                idempotency: 4,
            },
            8,
        );

        store
            .ingest(event("agent-a", "task-a", 0.25), Transport::Http)
            .await
            .unwrap();
        store
            .ingest(event("agent-b", "task-b", 0.75), Transport::Http)
            .await
            .unwrap();

        let snapshot = store.snapshot().await;
        assert_eq!(snapshot.caches.agents.evictions, 1);
        assert_eq!(snapshot.caches.tasks.evictions, 1);
        assert_eq!(snapshot.caches.events.evictions, 1);
    }

    #[tokio::test]
    async fn authoritative_task_creation_enriches_inferred_state_without_erasing_progress() {
        let store = Store::new(
            CacheConfig {
                agents: 2,
                goals: 2,
                tasks: 2,
                lessons: 2,
                events: 8,
                idempotency: 16,
            },
            8,
        );

        store
            .ingest(event("agent-a", "task-a", 0.6), Transport::Udp)
            .await
            .unwrap();

        let mut created = EventEnvelope::new(
            AgentRef {
                agent_id: "agent-a".to_owned(),
                provider: "openai".to_owned(),
                model: "gpt".to_owned(),
                instance_id: None,
            },
            AgentEvent::TaskCreated(TaskSpec {
                task_id: "task-a".to_owned(),
                title: "Authoritative title".to_owned(),
                goal_id: Some("goal-a".to_owned()),
                depends_on: vec!["task-prerequisite".to_owned()],
                tags: vec!["analysis".to_owned()],
                expected_outcome: Some("Validated implementation".to_owned()),
            }),
        );
        created.occurred_at += chrono::Duration::milliseconds(1);
        store.ingest(created, Transport::Http).await.unwrap();

        let snapshot = store.snapshot().await;
        let task = &snapshot.tasks[0];
        assert_eq!(task.task.title, "Authoritative title");
        assert_eq!(task.task.goal_id.as_deref(), Some("goal-a"));
        assert_eq!(task.progress, 0.6);
        assert_eq!(
            task.progress_summary.as_deref(),
            Some("Made measurable progress.")
        );
        assert_eq!(task.next_action.as_deref(), Some("Continue validation."));
        assert_eq!(task.status, TaskStatus::Running);
        assert!(!task.inferred_from_out_of_order_event);
    }

    #[tokio::test]
    async fn identical_task_ids_are_isolated_between_agents() {
        let store = Store::new(
            CacheConfig {
                agents: 4,
                goals: 4,
                tasks: 4,
                lessons: 4,
                events: 8,
                idempotency: 16,
            },
            8,
        );

        store
            .ingest(event("agent-a", "task-shared", 0.25), Transport::Http)
            .await
            .unwrap();
        store
            .ingest(event("agent-b", "task-shared", 0.75), Transport::Tcp)
            .await
            .unwrap();

        let snapshot = store.snapshot().await;
        assert_eq!(snapshot.tasks.len(), 2);
        let agent_a = snapshot
            .tasks
            .iter()
            .find(|task| task.agent_id == "agent-a")
            .unwrap();
        let agent_b = snapshot
            .tasks
            .iter()
            .find(|task| task.agent_id == "agent-b")
            .unwrap();
        assert_eq!(agent_a.progress, 0.25);
        assert_eq!(agent_b.progress, 0.75);
    }

    #[tokio::test]
    async fn completion_counters_are_idempotent_and_reconcile_corrected_outcomes() {
        let store = Store::new(
            CacheConfig {
                agents: 2,
                goals: 2,
                tasks: 2,
                lessons: 2,
                events: 8,
                idempotency: 16,
            },
            8,
        );

        store
            .ingest(
                completion_event("agent-a", "task-a", TaskOutcome::Failed),
                Transport::Http,
            )
            .await
            .unwrap();
        store
            .ingest(
                completion_event("agent-a", "task-a", TaskOutcome::Failed),
                Transport::Tcp,
            )
            .await
            .unwrap();

        let failed = store.snapshot().await;
        assert_eq!(failed.agents[0].completed_tasks, 0);
        assert_eq!(failed.agents[0].failed_tasks, 1);

        store
            .ingest(
                completion_event("agent-a", "task-a", TaskOutcome::Succeeded),
                Transport::WebSocket,
            )
            .await
            .unwrap();
        store
            .ingest(
                completion_event("agent-a", "task-a", TaskOutcome::Partial),
                Transport::Udp,
            )
            .await
            .unwrap();

        let corrected = store.snapshot().await;
        assert_eq!(corrected.agents[0].completed_tasks, 1);
        assert_eq!(corrected.agents[0].failed_tasks, 0);
        assert_eq!(corrected.tasks[0].outcome, Some(TaskOutcome::Partial));
    }

    #[tokio::test]
    async fn a_newer_progress_event_reopens_a_completed_task_without_counter_drift() {
        let store = Store::new(
            CacheConfig {
                agents: 2,
                goals: 2,
                tasks: 2,
                lessons: 2,
                events: 8,
                idempotency: 16,
            },
            8,
        );

        let completed = completion_event("agent-a", "task-a", TaskOutcome::Succeeded);
        let mut progress = event("agent-a", "task-a", 0.8);
        progress.occurred_at = completed.occurred_at + chrono::Duration::milliseconds(1);

        store.ingest(completed, Transport::Http).await.unwrap();
        store.ingest(progress, Transport::Tcp).await.unwrap();

        let snapshot = store.snapshot().await;
        assert_eq!(snapshot.agents[0].completed_tasks, 0);
        assert_eq!(snapshot.tasks[0].status, TaskStatus::Running);
        assert_eq!(snapshot.tasks[0].outcome, None);
    }

    #[tokio::test]
    async fn idempotency_window_is_independent_from_recent_event_timeline() {
        let store = Store::new(
            CacheConfig {
                agents: 4,
                goals: 4,
                tasks: 4,
                lessons: 4,
                events: 1,
                idempotency: 8,
            },
            8,
        );
        let original = event("agent-a", "task-a", 0.25);
        let original_id = original.event_id;
        store
            .ingest(original.clone(), Transport::Http)
            .await
            .unwrap();
        store
            .ingest(event("agent-b", "task-b", 0.50), Transport::Tcp)
            .await
            .unwrap();

        let duplicate = store.ingest(original, Transport::WebSocket).await.unwrap();
        let snapshot = store.snapshot().await;

        assert_eq!(duplicate.event_id, original_id);
        assert!(duplicate.duplicate);
        assert_eq!(snapshot.counters.accepted, 2);
        assert_eq!(snapshot.counters.duplicate, 1);
        assert_eq!(snapshot.caches.events.length, 1);
        assert_eq!(snapshot.caches.idempotency.length, 2);
    }

    #[tokio::test]
    async fn stale_task_definition_does_not_replace_a_newer_authoritative_definition() {
        let store = Store::new(
            CacheConfig {
                agents: 2,
                goals: 2,
                tasks: 2,
                lessons: 2,
                events: 8,
                idempotency: 16,
            },
            8,
        );
        let agent = AgentRef {
            agent_id: "agent-a".to_owned(),
            provider: "openai".to_owned(),
            model: "gpt".to_owned(),
            instance_id: None,
        };
        let newer_at = Utc::now();
        let mut newer = EventEnvelope::new(
            agent.clone(),
            AgentEvent::TaskCreated(TaskSpec {
                task_id: "task-a".to_owned(),
                title: "New definition".to_owned(),
                goal_id: None,
                depends_on: Vec::new(),
                tags: vec!["new".to_owned()],
                expected_outcome: None,
            }),
        );
        newer.occurred_at = newer_at;
        let mut stale = EventEnvelope::new(
            agent,
            AgentEvent::TaskCreated(TaskSpec {
                task_id: "task-a".to_owned(),
                title: "Stale definition".to_owned(),
                goal_id: None,
                depends_on: Vec::new(),
                tags: vec!["stale".to_owned()],
                expected_outcome: None,
            }),
        );
        stale.occurred_at = newer_at - chrono::Duration::seconds(1);

        store.ingest(newer, Transport::Http).await.unwrap();
        store.ingest(stale, Transport::Tcp).await.unwrap();

        let snapshot = store.snapshot().await;
        assert_eq!(snapshot.tasks[0].task.title, "New definition");
        assert_eq!(snapshot.tasks[0].task.tags, vec!["new".to_owned()]);
    }

    #[test]
    fn store_debug_output_does_not_dump_projection_state() {
        let store = Store::new(
            CacheConfig {
                agents: 1,
                goals: 1,
                tasks: 1,
                lessons: 1,
                events: 1,
                idempotency: 4,
            },
            1,
        );

        assert_eq!(format!("{store:?}"), "Store { .. }");
    }
}
