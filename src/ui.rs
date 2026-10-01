use leptos::prelude::*;

use crate::daemon::BoundAddresses;

pub fn dashboard(addresses: BoundAddresses, reads_protected: bool) -> String {
    let http_endpoint = "/api/v1/events";
    let ws_endpoint = "/ws/agent";
    let tcp_endpoint = format!("<daemon-host>:{}", addresses.tcp.port());
    let udp_endpoint = format!("<daemon-host>:{}", addresses.udp.port());
    let protection = if reads_protected {
        "Read API protected"
    } else {
        "Read API open"
    };

    let body = view! {
        <main class="shell">
            <header class="hero">
                <div>
                    <p class="eyebrow">"META-AGENT CONTROL PLANE"</p>
                    <h1>"Observe the work. Improve the worker."</h1>
                    <p class="lede">
                        "A bounded, local-first view of agent goals, tasks, progress, explicit reflection, and learned lessons."
                    </p>
                </div>
                <div class="connection-card">
                    <span id="live-indicator" class="indicator offline"></span>
                    <div>
                        <strong id="live-label">"Connecting"</strong>
                        <small>{protection}</small>
                    </div>
                </div>
            </header>

            <section class="toolbar panel">
                <label for="auth-token">"Read token"</label>
                <input id="auth-token" type="password" autocomplete="off" placeholder="Only needed when read protection is enabled" />
                <button id="save-token" type="button">"Apply token"</button>
                <button id="refresh" class="secondary" type="button">"Refresh"</button>
                <span id="last-updated" class="muted">"No snapshot yet"</span>
            </section>

            <section id="error-banner" class="error-banner hidden"></section>

            <section class="stat-grid">
                <article class="stat panel"><span>"Agents"</span><strong id="stat-agents">"0"</strong></article>
                <article class="stat panel"><span>"Active tasks"</span><strong id="stat-active">"0"</strong></article>
                <article class="stat panel"><span>"Lessons"</span><strong id="stat-lessons">"0"</strong></article>
                <article class="stat panel"><span>"Revision"</span><strong id="stat-revision">"0"</strong></article>
                <article class="stat panel"><span>"Accepted"</span><strong id="stat-accepted">"0"</strong></article>
                <article class="stat panel"><span>"Rejected"</span><strong id="stat-rejected">"0"</strong></article>
            </section>

            <section class="two-column">
                <article class="panel padded">
                    <div class="section-title"><h2>"Agents"</h2><span>"Live state and latest reflection"</span></div>
                    <div id="agents" class="card-list empty-state">"No agents have checked in."</div>
                </article>
                <article class="panel padded">
                    <div class="section-title"><h2>"Cache pressure"</h2><span>"Bounded LRU memory"</span></div>
                    <div id="caches" class="cache-list empty-state">"Waiting for state."</div>
                </article>
            </section>

            <section class="panel padded">
                <div class="section-title"><h2>"Task graph"</h2><span>"Progress, blockers, and next actions"</span></div>
                <div class="table-wrap">
                    <table>
                        <thead><tr><th>"Task"</th><th>"Agent"</th><th>"Status"</th><th>"Progress"</th><th>"Next action / blocker"</th></tr></thead>
                        <tbody id="tasks"><tr><td colspan="5" class="empty-state">"No tasks observed."</td></tr></tbody>
                    </table>
                </div>
            </section>

            <section class="two-column">
                <article class="panel padded">
                    <div class="section-title"><h2>"Learned lessons"</h2><span>"Reusable, evidence-bearing claims"</span></div>
                    <div id="lessons" class="card-list empty-state">"No lessons recorded."</div>
                </article>
                <article class="panel padded">
                    <div class="section-title"><h2>"Transport endpoints"</h2><span>"One protocol, four ingress paths"</span></div>
                    <dl class="endpoint-list">
                        <div><dt>"HTTP"</dt><dd><code>{http_endpoint}</code></dd></div>
                        <div><dt>"WebSocket"</dt><dd><code>{ws_endpoint}</code></dd></div>
                        <div><dt>"TCP / NDJSON"</dt><dd><code>{tcp_endpoint}</code></dd></div>
                        <div><dt>"UDP / JSON"</dt><dd><code>{udp_endpoint}</code></dd></div>
                    </dl>
                    <p class="muted protocol-note">
                        "Introspection means concise summaries, confidence, assumptions, evidence, risks, and next actions—not private hidden reasoning."
                    </p>
                </article>
            </section>

            <section class="panel padded">
                <div class="section-title"><h2>"Recent events"</h2><span>"Newest first"</span></div>
                <div id="events" class="event-stream empty-state">"No events received."</div>
            </section>
        </main>
    }
    .to_html();

    format!(
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"color-scheme\" content=\"dark\"><title>Meta-Agent Control Plane</title><style>{CSS}</style></head><body>{body}<script>{SCRIPT}</script></body></html>"
    )
}

const CSS: &str = r#"
:root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#edf3ff;background:#081019;--panel:#101b27;--line:#26384b;--muted:#91a5ba;--accent:#71e6c2;--blue:#6db5ff;--danger:#ff7285}*{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(circle at 12% 0,#17314b 0,transparent 35%),radial-gradient(circle at 95% 8%,#193d34 0,transparent 28%),#081019;color:#edf3ff}.shell{width:min(1480px,calc(100% - 32px));margin:0 auto;padding:34px 0 56px}.hero{display:flex;align-items:flex-start;justify-content:space-between;gap:28px;margin-bottom:24px}.eyebrow{margin:0 0 10px;color:var(--accent);font-size:.74rem;font-weight:800;letter-spacing:.18em}.hero h1{font-size:clamp(2.2rem,5vw,5.4rem);line-height:.94;max-width:900px;margin:0;letter-spacing:-.055em}.lede{max-width:770px;color:var(--muted);font-size:1.08rem;line-height:1.65}.panel{background:linear-gradient(155deg,rgba(21,35,51,.96),rgba(12,23,34,.96));border:1px solid var(--line);border-radius:18px;box-shadow:0 18px 50px rgba(0,0,0,.17)}.padded{padding:20px}.connection-card{min-width:190px;display:flex;align-items:center;gap:12px;padding:15px 17px;border:1px solid var(--line);background:rgba(9,19,29,.78);border-radius:14px}.connection-card strong,.connection-card small{display:block}.connection-card small{color:var(--muted);margin-top:3px}.indicator{width:11px;height:11px;border-radius:50%;display:inline-block;background:var(--danger);box-shadow:0 0 0 5px rgba(255,114,133,.09)}.indicator.online{background:var(--accent);box-shadow:0 0 0 5px rgba(113,230,194,.11)}.toolbar{display:grid;grid-template-columns:auto minmax(220px,1fr) auto auto minmax(180px,auto);align-items:center;gap:10px;padding:13px 15px;margin-bottom:16px}.toolbar label{color:var(--muted);font-size:.82rem;font-weight:700}.toolbar input{min-width:0;background:#09131e;border:1px solid var(--line);color:#edf3ff;border-radius:10px;padding:10px 12px}.toolbar button{border:0;border-radius:10px;background:var(--accent);color:#06120e;font-weight:800;padding:10px 14px;cursor:pointer}.toolbar button.secondary{background:#203449;color:#edf3ff}.muted{color:var(--muted);font-size:.84rem}.error-banner{background:rgba(255,114,133,.12);border:1px solid rgba(255,114,133,.45);color:#ffd7dd;padding:12px 16px;border-radius:12px;margin-bottom:16px}.hidden{display:none}.stat-grid{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:12px;margin-bottom:16px}.stat{padding:17px}.stat span{display:block;color:var(--muted);font-size:.78rem;text-transform:uppercase;letter-spacing:.08em}.stat strong{display:block;font-size:2rem;margin-top:8px}.two-column{display:grid;grid-template-columns:minmax(0,1.45fr) minmax(300px,.75fr);gap:16px;margin:16px 0}.section-title{display:flex;align-items:baseline;justify-content:space-between;gap:12px;margin-bottom:16px}.section-title h2{margin:0;font-size:1.1rem}.section-title span{color:var(--muted);font-size:.78rem}.card-list,.cache-list,.event-stream{display:grid;gap:10px}.agent-card,.lesson-card,.cache-row,.event-row{padding:13px;background:rgba(7,16,25,.64);border:1px solid var(--line);border-radius:12px}.card-head{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}.card-head small,.micro{display:block;color:var(--muted);font-size:.76rem;margin-top:4px}.pill{display:inline-flex;align-items:center;border:1px solid var(--line);border-radius:999px;padding:4px 8px;color:#c9daf0;font-size:.7rem}.confidence{color:var(--accent);font-weight:800}.reflection{margin-top:10px;color:#c9daf0;font-size:.84rem;line-height:1.45}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;min-width:850px}th,td{text-align:left;padding:13px 10px;border-bottom:1px solid var(--line);font-size:.82rem}th{color:var(--muted);font-size:.72rem;text-transform:uppercase;letter-spacing:.06em}.task-title small{display:block;color:var(--muted);margin-top:4px}.bar{height:7px;background:#08131e;border-radius:999px;overflow:hidden}.bar span{display:block;height:100%;background:linear-gradient(90deg,var(--blue),var(--accent));border-radius:999px}.progress-label{display:flex;justify-content:space-between;color:var(--muted);font-size:.72rem;margin-bottom:5px}.cache-row{display:grid;grid-template-columns:70px 1fr auto;gap:10px;align-items:center}.cache-row small{color:var(--muted)}.endpoint-list{display:grid;gap:10px}.endpoint-list div{display:grid;grid-template-columns:100px 1fr;gap:10px}.endpoint-list dt{color:var(--muted)}code{color:var(--accent);overflow-wrap:anywhere}.event-row{display:grid;grid-template-columns:165px 120px minmax(150px,.65fr) minmax(180px,1fr) auto;gap:12px;align-items:center;font-size:.78rem}.event-kind{color:var(--blue);font-family:ui-monospace,SFMono-Regular,Menlo,monospace}.empty-state{color:var(--muted);padding:18px 4px}.danger-text{color:#ff9aaa}@media(max-width:1000px){.stat-grid{grid-template-columns:repeat(3,1fr)}.two-column{grid-template-columns:1fr}.hero{display:block}.connection-card{margin-top:18px;width:max-content}.toolbar{grid-template-columns:1fr 1fr}.toolbar label,.toolbar input,.toolbar .muted{grid-column:1/-1}.event-row{grid-template-columns:1fr 1fr}}@media(max-width:620px){.shell{width:min(100% - 20px,1480px);padding-top:22px}.stat-grid{grid-template-columns:repeat(2,1fr)}.toolbar{display:flex;align-items:stretch;flex-direction:column}.hero h1{font-size:2.65rem}.section-title{display:block}}
"#;

const SCRIPT: &str = r#"
(() => {
  const state = { socket: null, retry: 0, snapshot: null, refreshing: false, dirty: false, refreshTimer: null };
  const $ = (id) => document.getElementById(id);
  const esc = (value) => String(value ?? '').replace(/[&<>'"]/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));
  const pct = (value) => `${Math.round(Number(value || 0) * 100)}%`;
  const token = () => sessionStorage.getItem('meta-agent-read-token') || '';
  const authHeaders = () => token() ? { Authorization: `Bearer ${token()}` } : {};
  const badge = (value) => `<span class="pill">${esc(value)}</span>`;
  const showError = (message) => { const el = $('error-banner'); el.textContent = message; el.classList.remove('hidden'); };
  const clearError = () => $('error-banner').classList.add('hidden');
  const setConnected = (connected, label) => { $('live-indicator').className = `indicator ${connected ? 'online' : 'offline'}`; $('live-label').textContent = label; };

  async function refresh() {
    if (state.refreshing) { state.dirty = true; return; }
    state.refreshing = true;
    try {
      const response = await fetch('/api/v1/snapshot', { headers: authHeaders(), cache: 'no-store' });
      if (!response.ok) throw new Error(`Snapshot failed: HTTP ${response.status}`);
      state.snapshot = await response.json();
      render(state.snapshot);
      clearError();
    } catch (error) {
      showError(error.message || String(error));
    } finally {
      state.refreshing = false;
      if (state.dirty) { state.dirty = false; scheduleRefresh(); }
    }
  }

  function scheduleRefresh() {
    if (state.refreshTimer) return;
    state.refreshTimer = setTimeout(() => { state.refreshTimer = null; refresh(); }, 100);
  }

  function render(snapshot) {
    const active = snapshot.tasks.filter((task) => ['pending','running','blocked'].includes(task.status)).length;
    $('stat-agents').textContent = snapshot.agents.length;
    $('stat-active').textContent = active;
    $('stat-lessons').textContent = snapshot.lessons.length;
    $('stat-revision').textContent = snapshot.revision;
    $('stat-accepted').textContent = snapshot.counters.accepted;
    $('stat-rejected').textContent = snapshot.counters.rejected;
    $('last-updated').textContent = `Updated ${new Date(snapshot.generated_at).toLocaleTimeString()}`;
    renderAgents(snapshot.agents);
    renderTasks(snapshot.tasks);
    renderLessons(snapshot.lessons);
    renderCaches(snapshot.caches);
    renderEvents(snapshot.recent_events);
  }

  function renderAgents(agents) {
    const target = $('agents');
    if (!agents.length) { target.className = 'card-list empty-state'; target.textContent = 'No agents have checked in.'; return; }
    target.className = 'card-list';
    target.innerHTML = agents.map((item) => {
      const reflection = item.latest_reflection;
      const detail = reflection ? `<div class="reflection"><span class="confidence">${pct(reflection.confidence)} confidence</span> · ${esc(reflection.summary)}${reflection.next_action ? `<span class="micro">Next: ${esc(reflection.next_action)}</span>` : ''}</div>` : '';
      return `<article class="agent-card"><div class="card-head"><div><strong>${esc(item.display_name)}</strong><small>${esc(item.agent.provider)} / ${esc(item.agent.model)} · ${esc(item.agent.agent_id)}</small></div>${badge(item.status)}</div><span class="micro">Last seen ${new Date(item.last_seen_at).toLocaleString()} · ${item.completed_tasks} completed · ${item.failed_tasks} failed</span>${detail}</article>`;
    }).join('');
  }

  function renderTasks(tasks) {
    const target = $('tasks');
    if (!tasks.length) { target.innerHTML = '<tr><td colspan="5" class="empty-state">No tasks observed.</td></tr>'; return; }
    target.innerHTML = tasks.map((item) => {
      const action = item.blocker ? `<span class="danger-text">Blocked: ${esc(item.blocker)}</span>` : esc(item.next_action || item.progress_summary || '—');
      return `<tr><td class="task-title"><strong>${esc(item.task.title)}</strong><small>${esc(item.task.task_id)}${item.inferred_from_out_of_order_event ? ' · inferred' : ''}</small></td><td>${esc(item.agent_id)}</td><td>${badge(item.status)}</td><td><div class="progress-label"><span>${pct(item.progress)}</span><span>attempt ${item.attempt}</span></div><div class="bar"><span style="width:${pct(item.progress)}"></span></div></td><td>${action}</td></tr>`;
    }).join('');
  }

  function renderLessons(lessons) {
    const target = $('lessons');
    if (!lessons.length) { target.className = 'card-list empty-state'; target.textContent = 'No lessons recorded.'; return; }
    target.className = 'card-list';
    target.innerHTML = lessons.map((item) => `<article class="lesson-card"><div class="card-head"><strong>${esc(item.lesson.statement)}</strong><span class="confidence">${pct(item.lesson.confidence)}</span></div><span class="micro">${esc(item.agent_id)} · observed ${item.observations} time${item.observations === 1 ? '' : 's'}${item.lesson.tags.length ? ` · ${item.lesson.tags.map(esc).join(', ')}` : ''}</span></article>`).join('');
  }

  function renderCaches(caches) {
    const target = $('caches');
    target.className = 'cache-list';
    target.innerHTML = Object.entries(caches).map(([name, value]) => `<div class="cache-row"><label>${esc(name)}</label><div class="bar"><span style="width:${pct(value.pressure)}"></span></div><small>${value.length}/${value.capacity} · ${value.evictions} evicted</small></div>`).join('');
  }

  function renderEvents(events) {
    const target = $('events');
    if (!events.length) { target.className = 'event-stream empty-state'; target.textContent = 'No events received.'; return; }
    target.className = 'event-stream';
    target.innerHTML = events.slice(0, 100).map((record) => `<div class="event-row"><time>${new Date(record.received_at).toLocaleString()}</time><span class="event-kind">${esc(record.event.kind)}</span><span>${esc(record.event.agent.agent_id)}</span><span>${esc(record.event.data?.summary || record.event.data?.title || record.event.data?.task_id || '')}</span><span class="pill">${esc(record.transport)}</span></div>`).join('');
  }

  function connect() {
    if (state.socket) { state.socket.onclose = null; state.socket.close(); }
    const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const socket = new WebSocket(`${scheme}//${location.host}/ws/ui`);
    state.socket = socket;
    setConnected(false, 'Connecting');
    socket.onopen = () => { socket.send(JSON.stringify({ token: token() })); };
    socket.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data);
        if (message.kind === 'authenticated') { state.retry = 0; setConnected(true, 'Live'); refresh(); return; }
        if (message.error) { showError(message.message || message.error); return; }
      } catch (_) {}
      scheduleRefresh();
    };
    socket.onerror = () => setConnected(false, 'Connection error');
    socket.onclose = () => {
      if (state.socket !== socket) return;
      setConnected(false, 'Reconnecting');
      const delay = Math.min(15000, 600 * (2 ** state.retry++));
      setTimeout(connect, delay);
    };
  }

  $('auth-token').value = token();
  $('save-token').addEventListener('click', () => {
    const value = $('auth-token').value;
    if (value) sessionStorage.setItem('meta-agent-read-token', value);
    else sessionStorage.removeItem('meta-agent-read-token');
    state.retry = 0;
    connect();
    refresh();
  });
  $('refresh').addEventListener('click', refresh);
  refresh();
  connect();
})();
"#;

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};

    use super::*;

    #[test]
    fn renders_operator_dashboard() {
        let address = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 8787);
        let html = dashboard(
            BoundAddresses {
                http: address,
                tcp: address,
                udp: address,
            },
            true,
        );
        assert!(html.contains("Meta-Agent Control Plane"));
        assert!(html.contains("Learned lessons"));
        assert!(html.contains("/ws/ui"));
    }
}
