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
