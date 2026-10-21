'use strict';

/* =====================================================================================
 * Acme AI Showcase - dependency-free UI. Each demo section talks to its REST API.
 * ===================================================================================== */

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const pretty = (v) => { try { return JSON.stringify(typeof v === 'string' ? JSON.parse(v) : v, null, 2); } catch { return String(v); } };
const ms = (n) => (n >= 1000 ? (n / 1000).toFixed(1) + ' s' : n + ' ms');
const pill = (text, kind = 'info') => `<span class="pill ${kind}">${esc(text)}</span>`;

let samples = {};

/** Minimal, safe Markdown: escapes first, then formats code blocks, headings, lists, bold, inline code. */
function md(text) {
  const blocks = [];
  let src = esc(text ?? '').replace(/```(\w*)\n?([\s\S]*?)```/g, (_, lang, code) => {
    blocks.push(`<pre><code>${code.replace(/\n$/, '')}</code></pre>`);
    return `\u0000${blocks.length - 1}\u0000`;
  });
  const lines = src.split('\n');
  let html = '';
  let list = null;
  const closeList = () => { if (list) { html += `</${list}>`; list = null; } };
  for (const raw of lines) {
    const line = raw.trimEnd();
    let m;
    if ((m = line.match(/^#{1,4}\s+(.*)/))) { closeList(); html += `<h3>${m[1]}</h3>`; }
    else if ((m = line.match(/^\s*[-*•]\s+(.*)/))) { if (list !== 'ul') { closeList(); html += '<ul>'; list = 'ul'; } html += `<li>${m[1]}</li>`; }
    else if ((m = line.match(/^\s*\d+[.)]\s+(.*)/))) { if (list !== 'ol') { closeList(); html += '<ol>'; list = 'ol'; } html += `<li>${m[1]}</li>`; }
    else if (line.trim() === '') { closeList(); }
    else { closeList(); html += `<p>${line}</p>`; }
  }
  closeList();
  html = html
    .replace(/\*\*(.+?)\*\*/g, '<b>$1</b>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\u0000(\d+)\u0000/g, (_, i) => blocks[+i]);
  return `<div class="md">${html}</div>`;
}

async function api(path, { method = 'GET', body } = {}) {
  const isForm = body instanceof FormData;
  const res = await fetch(path, {
    method,
    headers: body && !isForm ? { 'Content-Type': 'application/json' } : {},
    body: isForm ? body : body !== undefined ? JSON.stringify(body) : undefined,
  });
  const type = res.headers.get('content-type') || '';
  const data = res.status === 204 ? null : type.includes('json') ? await res.json() : await res.text();
  if (!res.ok) {
    const err = new Error((data && (data.detail || data.title)) || res.statusText);
    err.problem = typeof data === 'object' ? data : { detail: data };
    err.status = res.status;
    throw err;
  }
  return { status: res.status, data };
}

function problemHtml(err) {
  const p = err.problem || {};
  return `<div class="error"><b>${esc(p.title || 'Request failed')}</b> (HTTP ${esc(err.status || '?')})<br>${esc(p.detail || err.message)}
    ${p.hint ? `<br><span class="meta">Hint: ${esc(p.hint)}</span>` : ''}</div>`;
}

async function busy(button, label, fn) {
  const original = button.innerHTML;
  button.disabled = true;
  button.innerHTML = `<span class="spinner"></span>${esc(label)}`;
  try { return await fn(); } finally { button.disabled = false; button.innerHTML = original; }
}

const loading = (el, text = 'Thinking…') => { el.innerHTML = `<div class="panel"><span class="spinner"></span>${esc(text)}</div>`; };

function exampleButtons(container, items, onPick, label = 'Try:') {
  container.innerHTML = items.length ? `<span class="label">${esc(label)}</span>` : '';
  items.forEach((item) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = item.label;
    b.title = item.text;
    b.addEventListener('click', () => onPick(item));
    container.appendChild(b);
  });
}

function table(columns, rows, empty = 'Nothing yet.') {
  if (!rows || !rows.length) return `<p class="empty">${esc(empty)}</p>`;
  return `<table><thead><tr>${columns.map((c) => `<th>${esc(c.label)}</th>`).join('')}</tr></thead><tbody>${rows
    .map((r) => `<tr>${columns.map((c) => `<td class="${c.cls || ''}">${c.html ? c.html(r) : esc(r[c.key])}</td>`).join('')}</tr>`)
    .join('')}</tbody></table>`;
}

function traceTable(trace) {
  const modes = { auto: 'ok', approved: 'ok', rejected: 'bad', mcp: 'info' };
  return table(
    [
      { label: 'Tool', html: (r) => `<b>${esc(r.tool)}</b>` },
      { label: 'Arguments', cls: 'wrap', html: (r) => `<code>${esc(pretty(r.arguments))}</code>` },
      { label: 'Result', cls: 'wrap', html: (r) => `<code>${esc(unwrapToolResult(r.result))}</code>` },
      { label: 'Mode', html: (r) => pill(r.mode, modes[r.mode] || 'info') },
    ],
    trace,
    'The model answered without calling any tool.'
  );
}

/** MCP results arrive as [{"text":"{json}"}]; show the inner JSON. */
function unwrapToolResult(result) {
  try {
    const parsed = JSON.parse(result);
    if (Array.isArray(parsed) && parsed.length && parsed[0].text) return pretty(parsed.map((c) => c.text).join('\n'));
    return pretty(parsed);
  } catch { return result; }
}

/* ===================================================================== routing */

const pages = {};
function showPage(id) {
  if (!document.getElementById(id) || !document.getElementById(id).classList.contains('page')) id = 'home';
  $$('.page').forEach((p) => { p.hidden = p.id !== id; });
  $$('#nav a').forEach((a) => a.classList.toggle('active', a.dataset.nav === id));
  if (pages[id] && !pages[id].loaded) { pages[id].loaded = true; pages[id].init?.(); }
  pages[id]?.show?.();
  window.scrollTo(0, 0);
}
window.addEventListener('hashchange', () => showPage(location.hash.slice(1)));

/* ===================================================================== home */

const FEATURES = [
  { id: 'chat', num: 1, title: 'Chat & memory', usecase: 'Support assistant that remembers the customer across restarts.', tags: ['ChatClient', 'JDBC memory', 'SSE streaming'] },
  { id: 'guardrails', num: 2, title: 'Guardrails', usecase: 'Bank chat: PII masking, injection blocking, topic & output guards.', tags: ['Advisors', 'SafeGuardAdvisor', 'ProblemDetail'] },
  { id: 'rag', num: 3, title: 'RAG with citations', usecase: 'Employee Q&A over the Acme handbook with sources.', tags: ['pgvector', 'Tika ETL', 'RetrievalAugmentationAdvisor'] },
  { id: 'helpdesk', num: 4, title: 'Tools + approvals', usecase: 'IT helpdesk agent with human-in-the-loop for risky actions.', tags: ['@Tool', 'ToolCallingAdvisor', 'HITL'] },
  { id: 'agents', num: 5, title: 'Agent patterns', usecase: 'Chain, routing, parallel, orchestrator-workers, evaluator-optimizer.', tags: ['Workflows', 'Virtual threads', 'Structured output'] },
  { id: 'extraction', num: 6, title: 'Structured extraction', usecase: 'Invoices and meeting notes into validated Java records.', tags: ['entity()', 'JSON schema', 'Bean Validation'] },
  { id: 'mcp', num: 7, title: 'MCP server + client', usecase: 'Travel Desk tools shared with any AI client.', tags: ['@McpTool', 'Streamable HTTP', 'MCP client'] },
  { id: 'observability', num: 8, title: 'Observability', usecase: 'Tokens, latency, tools and guardrails in Grafana; full traces.', tags: ['Micrometer', 'OpenTelemetry', 'Grafana LGTM'] },
  { id: 'evaluation', num: 9, title: 'LLM evaluation', usecase: 'RAG quality gate with LLM-as-judge, in the UI and in CI.', tags: ['RelevancyEvaluator', 'FactCheckingEvaluator', 'JUnit'] },
];

pages.home = {
  init() {
    $('#featureGrid').innerHTML = FEATURES.map((f) => `
      <a class="card" href="#${f.id}">
        <h3><span class="num">${f.num}</span>${esc(f.title)}</h3>
        <p>${esc(f.usecase)}</p>
        <div class="tags">${f.tags.map((t) => `<span class="tag">${esc(t)}</span>`).join('')}</div>
      </a>`).join('');
  },
};

async function loadStatus() {
  try {
    const { data } = await api('/api/status');
    const provider = data.llmBaseUrl.includes('localhost') ? 'local' : 'hosted';
    $('#status').innerHTML = `chat <b>${esc(data.chatModel)}</b> · embeddings <b>${esc(data.embeddingModel)}</b> · ${esc(provider)} <span class="meta">${esc(data.llmBaseUrl)}</span>`;
    if (data.grafanaUrl) $('#grafanaLink').href = data.grafanaUrl + '/d/acme-ai-overview';
  } catch { $('#status').textContent = 'backend not reachable'; }
}

/* ===================================================================== 1 chat */

pages.chat = {
  init() {
    exampleButtons($('#chatExamples'), samples.chat || [], (s) => { $('#chatInput').value = s.text; sendChat(); });
    $('#chatForm').addEventListener('submit', (e) => { e.preventDefault(); sendChat(); });
    $('#chatNew').addEventListener('click', () => { $('#chatConv').value = 'chat-' + Math.random().toString(36).slice(2, 10); loadHistory(); });
    $('#chatConv').addEventListener('change', loadHistory);
    $('#chatMemory').addEventListener('click', showMemory);
    $('#chatForget').addEventListener('click', async () => {
      await api(`/api/chat/${encodeURIComponent(conv())}`, { method: 'DELETE' });
      $('#chatMemoryOut').innerHTML = '';
      loadHistory();
    });
  },
  show: () => loadHistory(),
};
const conv = () => $('#chatConv').value.trim() || 'priya-demo';

function chatBubble(role, text) {
  const div = document.createElement('div');
  div.className = 'msg ' + role;
  div.textContent = text;
  $('#chatLog').appendChild(div);
  $('#chatLog').scrollTop = $('#chatLog').scrollHeight;
  return div;
}

async function loadHistory() {
  $('#chatLog').innerHTML = '';
  try {
    const { data } = await api(`/api/chat/${encodeURIComponent(conv())}/memory`);
    if (data.length) chatBubble('system', `Restored ${data.length} messages from Postgres for "${conv()}"`);
    data.forEach((m) => chatBubble(m.type === 'USER' ? 'user' : 'assistant', m.content));
  } catch (e) { chatBubble('system', 'Could not load history: ' + e.message); }
}

async function showMemory() {
  const { data } = await api(`/api/chat/${encodeURIComponent(conv())}/memory`);
  $('#chatMemoryOut').innerHTML = `<h2>Rows in <code>spring_ai_chat_memory</code> for "${esc(conv())}"</h2>` +
    table([{ label: 'Type', key: 'type' }, { label: 'Content', key: 'content', cls: 'wrap' }], data, 'No memory stored for this conversation.');
}

async function sendChat() {
  const text = $('#chatInput').value.trim();
  if (!text) return;
  $('#chatInput').value = '';
  chatBubble('user', text);
  const bubble = chatBubble('assistant', '');
  bubble.innerHTML = '<span class="spinner"></span>';
  const button = $('#chatForm button');
  button.disabled = true;
  try {
    const res = await fetch('/api/chat/stream', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId: conv(), message: text }),
    });
    if (!res.ok) throw new Error((await res.json()).detail || res.statusText);
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let started = false;
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const events = buffer.split(/\r?\n\r?\n/);
      buffer = events.pop();
      for (const evt of events) {
        let name = 'message';
        let data = '';
        evt.split(/\r?\n/).forEach((line) => {
          if (line.startsWith('event:')) name = line.slice(6).trim();
          else if (line.startsWith('data:')) data += line.slice(5);
        });
        if (!data) continue;
        const payload = JSON.parse(data);
        if (name === 'error') throw new Error(payload.message);
        if (name === 'message' && payload.t !== undefined) {
          if (!started) { bubble.textContent = ''; started = true; }
          bubble.textContent += payload.t;
          $('#chatLog').scrollTop = $('#chatLog').scrollHeight;
        }
      }
    }
    if (!started) bubble.textContent = '(empty response)';
  } catch (e) {
    bubble.className = 'msg system';
    bubble.textContent = 'Error: ' + e.message;
  } finally { button.disabled = false; }
}

/* ===================================================================== 2 guardrails */

pages.guardrails = {
  init() {
    exampleButtons($('#guardExamples'), samples.guardrails || [], (s) => { $('#guardInput').value = s.text; sendGuarded(); });
    $('#guardForm').addEventListener('submit', (e) => { e.preventDefault(); sendGuarded(); });
  },
};

async function sendGuarded() {
  const text = $('#guardInput').value.trim();
  if (!text) return;
  const out = document.createElement('div');
  $('#guardOut').prepend(out);
  loading(out, 'Running guardrails and model…');
  const highlight = (s) => esc(s).replace(/\[(EMAIL|CARD|PHONE|IBAN|SSN)\]/g, '<mark>[$1]</mark>');
  try {
    const { data } = await busy($('#guardForm button'), 'Sending', () => api('/api/guardrails/chat', { method: 'POST', body: { conversationId: 'bank-demo', message: text } }));
    const pii = Object.entries(data.piiMasked || {});
    const blocked = data.blockedBy;
    const kind = blocked ? 'warn' : data.outputActions.length ? 'warn' : 'ok';
    out.innerHTML = `<div class="panel ${kind}">
      <div class="meta">You: ${esc(text)}</div>
      <div>${blocked ? pill('answered by ' + blocked + ' guard — model not called', 'warn') : pill('passed input guards', 'ok')}
        ${pii.map(([k, v]) => pill(`${v}× ${k} masked`, 'warn')).join('')}
        ${data.outputActions.map((a) => pill('output guard: ' + a, 'bad')).join('')}</div>
      <h3>Assistant</h3>${md(data.reply)}
      ${data.llmInput ? `<h3>What the LLM saw</h3><div class="seen">${highlight(data.llmInput)}</div>` : ''}
    </div>`;
  } catch (e) {
    const p = e.problem || {};
    out.innerHTML = e.status === 400 && p.guard
      ? `<div class="panel bad"><div class="meta">You: ${esc(text)}</div>${pill('BLOCKED · ' + p.guard, 'bad')}${pill('rule: ' + p.rule, 'bad')}
          <p>${esc(p.detail)}</p><p class="meta">HTTP 400 · RFC 9457 problem detail · the request never reached the model or the memory.</p></div>`
      : problemHtml(e);
  }
}

/* ===================================================================== 3 rag */

pages.rag = {
  init() {
    exampleButtons($('#ragExamples'), samples.rag || [], (s) => { $('#ragInput').value = s.text; $('#ragCategory').value = s.category || ''; askRag(); });
    $('#ragForm').addEventListener('submit', (e) => { e.preventDefault(); askRag(); });
    $('#uploadForm').addEventListener('submit', uploadDoc);
    $('#reloadSamples').addEventListener('click', (e) => busy(e.currentTarget, 'Ingesting', async () => {
      await api('/api/docs/samples?force=true', { method: 'POST' });
      loadDocs();
    }).catch((err) => { $('#docsOut').innerHTML = problemHtml(err); }));
    loadDocs();
  },
};

async function askRag() {
  const question = $('#ragInput').value.trim();
  if (!question) return;
  const category = $('#ragCategory').value || null;
  loading($('#ragOut'), 'Retrieving and answering…');
  try {
    const { data } = await busy($('#ragForm button'), 'Asking', () => api('/api/rag/ask', { method: 'POST', body: { question, category } }));
    const answer = md(data.answer).replace(/\[(\d+)\]/g, '<sup class="pill info">$1</sup>');
    const cites = [...data.citations].sort((a, b) => (b.cited - a.cited) || (a.number - b.number));
    $('#ragOut').innerHTML = `<div class="panel ok">
      <div class="meta">Q: ${esc(question)} ${category ? pill('filter: category == ' + category) : ''}</div>
      ${answer}
      <h3>Retrieved sources (${data.citations.length})</h3>
      ${cites.map((c) => `<details class="cite ${c.cited ? 'cited' : ''}">
        <summary>[${c.number}] <b>${esc(c.source)}</b> ${pill(c.category)} ${c.cited ? pill('cited', 'ok') : '<span class="meta">retrieved, not cited</span>'}
          <span class="meta">similarity ${c.score != null ? c.score.toFixed(3) : '?'}</span></summary>
        <pre class="code">${esc(c.snippet)}</pre></details>`).join('') || '<p class="empty">No chunk passed the similarity threshold — the assistant refuses instead of guessing.</p>'}
    </div>`;
  } catch (e) { $('#ragOut').innerHTML = problemHtml(e); }
}

async function loadDocs() {
  try {
    const { data } = await api('/api/docs');
    $('#docsOut').innerHTML = table([
      { label: 'Source', key: 'source' },
      { label: 'Category', html: (r) => pill(r.category) },
      { label: 'Chunks', key: 'chunks' },
      { label: 'Ingested', html: (r) => esc(String(r.ingestedAt).replace('T', ' ').slice(0, 19)) },
      { label: '', html: (r) => `<button class="small reject" data-del="${r.id}">Delete</button>` },
    ], data, 'Knowledge base is empty — click "Reload sample handbook".');
    $$('[data-del]', $('#docsOut')).forEach((b) => b.addEventListener('click', async () => {
      await api('/api/docs/' + b.dataset.del, { method: 'DELETE' });
      loadDocs();
    }));
  } catch (e) { $('#docsOut').innerHTML = problemHtml(e); }
}

async function uploadDoc(e) {
  e.preventDefault();
  const file = $('#uploadFile').files[0];
  if (!file) return;
  const form = new FormData();
  form.append('file', file);
  form.append('category', $('#uploadCategory').value);
  try {
    await busy($('#uploadForm button[type=submit]'), 'Ingesting', () => api('/api/docs', { method: 'POST', body: form }));
    $('#uploadFile').value = '';
    loadDocs();
  } catch (err) { $('#docsOut').insertAdjacentHTML('afterbegin', problemHtml(err)); }
}

/* ===================================================================== 4 helpdesk */

pages.helpdesk = {
  init() {
    exampleButtons($('#helpExamples'), samples.helpdesk || [], (s) => { $('#helpInput').value = s.text; sendHelpdesk(); });
    $('#helpForm').addEventListener('submit', (e) => { e.preventDefault(); sendHelpdesk(); });
  },
  show: () => refreshHelpdesk(),
};

function agentResultHtml(result, request) {
  if (result.status === 'PENDING_APPROVAL') {
    return `<div class="panel warn">
      ${request ? `<div class="meta">You: ${esc(request)}</div>` : ''}
      ${pill('HTTP 202 · waiting for human approval', 'warn')}
      <h3>The agent wants to run</h3>
      ${result.proposedCalls.map((c) => `<p><b>${esc(c.tool)}</b> <code>${esc(c.arguments)}</code><br><span class="meta">${esc(c.approvalReason || 'batched with a sensitive call')}</span></p>`).join('')}
      <div class="row">
        <button class="approve" data-approve="${result.approvalId}">Approve</button>
        <button class="reject" data-reject="${result.approvalId}">Reject…</button>
      </div>
      ${result.toolTrace.length ? `<h3>Tools already executed</h3>${traceTable(result.toolTrace)}` : ''}
    </div>`;
  }
  return `<div class="panel ok">
    ${request ? `<div class="meta">You: ${esc(request)}</div>` : ''}
    ${md(result.reply)}
    <h3>Tool calls</h3>${traceTable(result.toolTrace)}
  </div>`;
}

function bindDecisionButtons(root) {
  $$('[data-approve]', root).forEach((b) => b.addEventListener('click', () => decide(b.dataset.approve, 'approve', b)));
  $$('[data-reject]', root).forEach((b) => b.addEventListener('click', () => {
    const reason = prompt('Reason for rejecting (sent back to the agent):', 'Not allowed without a manager ticket');
    if (reason !== null) decide(b.dataset.reject, 'reject', b, reason);
  }));
}

async function sendHelpdesk() {
  const text = $('#helpInput').value.trim();
  if (!text) return;
  loading($('#helpOut'), 'Agent is working (it may call several tools)…');
  try {
    const { data } = await busy($('#helpForm button'), 'Working', () => api('/api/helpdesk/chat', { method: 'POST', body: { message: text } }));
    $('#helpOut').innerHTML = agentResultHtml(data, text);
    bindDecisionButtons($('#helpOut'));
  } catch (e) { $('#helpOut').innerHTML = problemHtml(e); }
  refreshHelpdesk();
}

async function decide(id, action, button, reason) {
  const qs = action === 'reject' && reason ? '?reason=' + encodeURIComponent(reason) : '';
  try {
    const { data } = await busy(button, action === 'approve' ? 'Running' : 'Rejecting', () => api(`/api/approvals/${id}/${action}${qs}`, { method: 'POST' }));
    $('#helpOut').innerHTML = `<p class="meta">Human decision: <b>${action}d</b></p>` + agentResultHtml(data);
    bindDecisionButtons($('#helpOut'));
  } catch (e) { $('#helpOut').innerHTML = problemHtml(e); }
  refreshHelpdesk();
}

async function refreshHelpdesk() {
  try {
    const [approvals, audit, tickets] = await Promise.all([api('/api/approvals'), api('/api/helpdesk/audit'), api('/api/helpdesk/tickets')]);
    $('#approvalsOut').innerHTML = table([
      { label: 'Request', key: 'request', cls: 'wrap' },
      { label: 'Proposed', html: (r) => r.proposedCalls.map((c) => `<b>${esc(c.tool)}</b> <code>${esc(c.arguments)}</code>`).join('<br>') },
      { label: '', html: (r) => `<button class="small approve" data-approve="${r.id}">Approve</button> <button class="small reject" data-reject="${r.id}">Reject</button>` },
    ], approvals.data, 'No actions waiting for approval.');
    bindDecisionButtons($('#approvalsOut'));
    $('#auditOut').innerHTML = table([
      { label: 'When', html: (r) => esc(String(r.createdAt).slice(11, 19)) },
      { label: 'Actor', key: 'actor' },
      { label: 'Action', html: (r) => pill(r.action, r.action === 'REJECTED' ? 'bad' : r.action === 'APPROVED' ? 'ok' : 'info') },
      { label: 'Details', key: 'details', cls: 'wrap' },
    ], audit.data.slice(0, 12), 'No actions yet.');
    $('#ticketsOut').innerHTML = table([
      { label: 'Ticket', html: (r) => `<b>${esc(r.ticketNo)}</b>` },
      { label: 'Employee', key: 'username' },
      { label: 'Asset', key: 'assetTag' },
      { label: 'Category', key: 'category' },
      { label: 'Priority', html: (r) => pill(r.priority, r.priority === 'HIGH' ? 'bad' : r.priority === 'MEDIUM' ? 'warn' : 'info') },
      { label: 'Status', key: 'status' },
      { label: 'Summary', key: 'summary', cls: 'wrap' },
    ], tickets.data);
    updateApprovalBadge(approvals.data.length);
  } catch (e) { $('#ticketsOut').innerHTML = problemHtml(e); }
}

function updateApprovalBadge(n) {
  $('#approvalBadge').hidden = !n;
  $('#approvalBadge').textContent = n;
}
setInterval(async () => { try { updateApprovalBadge((await api('/api/approvals')).data.length); } catch { /* backend down */ } }, 10000);

/* ===================================================================== 5 agents */

const PATTERNS = {
  chain: { desc: 'Prompt chaining — Marketing copy pipeline: product notes → features → draft → brand voice → final copy. Each step is a small, focused LLM call.', hint: '4 sequential LLM calls' },
  routing: { desc: 'Routing — Support inbox triage: a classifier (structured output) picks BILLING / TECHNICAL / ACCOUNT, then a specialist prompt writes the reply.', hint: '2 LLM calls per email' },
  parallel: { desc: 'Parallelization — Change-impact analysis: the same announcement is analysed for 4 stakeholder groups at once on virtual threads.', hint: '4 LLM calls in parallel' },
  orchestrator: { desc: 'Orchestrator-workers — Launch content kit: an orchestrator plans the pieces at runtime (structured output); workers write each piece in parallel.', hint: '1 planning call + 2–4 worker calls' },
  evaluator: { desc: 'Evaluator-optimizer — Code generator with reviewer: generate Java code, review it against criteria, revise until PASS (max 3 rounds).', hint: 'up to 6 LLM calls — slow on a local model' },
};
let pattern = 'chain';

pages.agents = {
  init() {
    $$('#agentTabs button').forEach((b) => b.addEventListener('click', () => selectPattern(b.dataset.pattern)));
    $('#agentRun').addEventListener('click', () => runAgent());
    selectPattern('chain');
  },
};

function selectPattern(p) {
  pattern = p;
  $$('#agentTabs button').forEach((b) => b.classList.toggle('active', b.dataset.pattern === p));
  $('#agentDesc').textContent = PATTERNS[p].desc;
  $('#agentHint').textContent = PATTERNS[p].hint;
  $('#agentOut').innerHTML = '';
  const sample = (samples.agents || {})[p];
  if (p === 'routing') {
    const emails = (sample || []).map((t, i) => ({ label: `Email ${i + 1}`, text: t }));
    exampleButtons($('#agentExamples'), emails, (s) => { $('#agentInput').value = s.text; });
    const all = document.createElement('button');
    all.type = 'button';
    all.textContent = 'Triage all 5 emails';
    all.addEventListener('click', () => triageAll(all, sample));
    $('#agentExamples').appendChild(all);
    $('#agentInput').value = sample?.[0] || '';
  } else {
    $('#agentExamples').innerHTML = '';
    $('#agentInput').value = sample || '';
  }
}

async function runAgent() {
  const input = $('#agentInput').value.trim();
  if (!input) return;
  loading($('#agentOut'), 'Running the ' + pattern + ' workflow…');
  try {
    const { data } = await busy($('#agentRun'), 'Running', () => api('/api/agents/' + pattern, { method: 'POST', body: { input } }));
    $('#agentOut').innerHTML = renderAgent(pattern, data);
  } catch (e) { $('#agentOut').innerHTML = problemHtml(e); }
}

async function triageAll(button, emails) {
  $('#agentOut').innerHTML = '';
  await busy(button, 'Triaging', async () => {
    for (const email of emails) {
      const holder = document.createElement('div');
      $('#agentOut').appendChild(holder);
      loading(holder, 'Classifying: ' + email.slice(0, 60) + '…');
      try {
        const { data } = await api('/api/agents/routing', { method: 'POST', body: { input: email } });
        holder.innerHTML = renderAgent('routing', data, email);
      } catch (e) { holder.innerHTML = problemHtml(e); }
    }
  });
}

function renderAgent(p, data, input) {
  switch (p) {
    case 'chain':
      return `<div class="steps">${data.map((s) => `<div class="panel"><h3>${esc(s.name)} <span class="meta">${ms(s.millis)}</span></h3>${md(s.output)}</div>`).join('')}</div>`;
    case 'routing': {
      const colors = { BILLING: 'warn', TECHNICAL: 'info', ACCOUNT: 'ok' };
      return `<div class="panel">${input ? `<div class="meta">${esc(input)}</div>` : ''}
        ${pill('route: ' + data.route, colors[data.route])} <span class="meta">classified in ${ms(data.classifyMillis)}, answered in ${ms(data.replyMillis)}</span>
        <p class="meta">Why: ${esc(data.reason)}</p>${md(data.reply)}</div>`;
    }
    case 'parallel': {
      const max = Math.max(...data.analyses.map((a) => a.millis), data.wallClockMillis);
      return `<div class="panel ok"><h3>Wall clock ${ms(data.wallClockMillis)} vs. ${ms(data.sumOfCallsMillis)} if run sequentially
          ${pill(Math.round(data.sumOfCallsMillis / Math.max(1, data.wallClockMillis) * 10) / 10 + '× faster', 'ok')}</h3>
        <div class="bargrid">${data.analyses.map((a) => `<span>${esc(a.name)}</span><div class="bar" style="width:${(a.millis / max) * 100}%"></div><span class="meta">${ms(a.millis)}</span>`).join('')}
          <span><b>total</b></span><div class="bar" style="width:${(data.wallClockMillis / max) * 100}%;background:var(--ok)"></div><span class="meta">${ms(data.wallClockMillis)}</span></div></div>
        <div class="cols">${data.analyses.map((a) => `<div class="panel"><h3>${esc(a.name)}</h3>${md(a.output)}</div>`).join('')}</div>`;
    }
    case 'orchestrator':
      return `<div class="panel"><h3>Orchestrator's plan</h3><p>${esc(data.plan.analysis)}</p>
          ${table([{ label: 'Worker task', key: 'type' }, { label: 'Instruction', key: 'description', cls: 'wrap' }], data.plan.tasks)}</div>
        ${data.outputs.map((o) => `<div class="panel"><h3>${esc(o.name)} <span class="meta">${ms(o.millis)}</span></h3>${md(o.output)}</div>`).join('')}`;
    case 'evaluator':
      return `<div class="panel ${data.passed ? 'ok' : 'warn'}">${data.passed ? pill('PASSED after ' + data.rounds.length + ' round(s)', 'ok') : pill('not passed within ' + data.rounds.length + ' rounds', 'warn')}</div>
        ${data.rounds.map((r) => `<div class="panel"><h3>Round ${r.round} ${pill(r.verdict, r.verdict === 'PASS' ? 'ok' : 'warn')} <span class="meta">${ms(r.millis)}</span></h3>
          <details><summary>Generated code</summary>${md(r.solution)}</details><h3>Reviewer feedback</h3>${md(r.feedback)}</div>`).join('')}`;
    default:
      return `<pre class="code">${esc(pretty(data))}</pre>`;
  }
}

/* ===================================================================== 6 extraction */

pages.extraction = {
  init() {
    $('#extractType').addEventListener('change', extractionExamples);
    $('#extractRun').addEventListener('click', runExtraction);
    extractionExamples();
  },
};

function extractionExamples() {
  const type = $('#extractType').value;
  const list = (samples.extraction || {})[type === 'invoice' ? 'invoice' : 'meeting'] || [];
  exampleButtons($('#extractExamples'), list, (s) => { $('#extractInput').value = s.text; }, 'Load sample:');
  $('#extractInput').value = list[0]?.text || '';
  $('#extractOut').innerHTML = '';
}

async function runExtraction() {
  const text = $('#extractInput').value.trim();
  if (!text) return;
  const type = $('#extractType').value;
  loading($('#extractOut'), 'Extracting and validating…');
  try {
    const { data } = await busy($('#extractRun'), 'Extracting', () => api('/api/extract/' + type, { method: 'POST', body: { text } }));
    const status = data.needsReview
      ? `<div class="panel warn">${pill('NEEDS HUMAN REVIEW', 'warn')} The document fails business rules even after re-checking — routed to a person instead of being "fixed" by the model.
          <ul>${data.issues.map((i) => `<li>${esc(i)}</li>`).join('')}</ul></div>`
      : `<div class="panel ok">${pill('VALID', 'ok')} Schema-valid JSON and all Bean Validation rules passed.</div>`;
    const attempts = table([
      { label: 'Attempt', key: 'attempt' },
      { label: 'Validation result', html: (r) => (r.violations.length ? r.violations.map((v) => pill(v, 'bad')).join(' ') : pill('valid', 'ok')) },
      { label: 'Time', html: (r) => ms(r.millis) },
    ], data.attempts);
    const view = type === 'invoice' ? invoiceView(data.data) : meetingView(data.data);
    $('#extractOut').innerHTML = `${status}<div class="panel">${view}</div><h3>Attempts</h3>${attempts}
      <details class="how"><summary>Raw JSON</summary><pre class="code">${esc(pretty(data.data))}</pre></details>`;
  } catch (e) { $('#extractOut').innerHTML = problemHtml(e); }
}

function invoiceView(inv) {
  if (!inv) return '<p class="empty">No data.</p>';
  return `<h3>${esc(inv.vendor)} · ${esc(inv.invoiceNumber)}</h3>
    <p class="meta">Invoice date ${esc(inv.invoiceDate)} · due ${esc(inv.dueDate ?? '—')} · currency ${esc(inv.currency)}</p>
    ${table([{ label: 'Description', key: 'description' }, { label: 'Qty', key: 'quantity' }, { label: 'Unit price', key: 'unitPrice' }, { label: 'Amount', key: 'amount' }], inv.lineItems)}
    <p><b>Subtotal</b> ${esc(inv.subtotal)} · <b>Tax</b> ${esc(inv.tax)} · <b>Total</b> ${esc(inv.total)} ${esc(inv.currency)}</p>`;
}

function meetingView(notes) {
  if (!notes) return '<p class="empty">No data.</p>';
  return `<h3>Summary</h3><p>${esc(notes.summary)}</p>
    <h3>Decisions</h3><ul>${(notes.decisions || []).map((d) => `<li>${esc(d)}</li>`).join('')}</ul>
    <h3>Action items</h3>${table([
      { label: 'Owner', html: (r) => `<b>${esc(r.owner)}</b>` },
      { label: 'Task', key: 'task', cls: 'wrap' },
      { label: 'Due', key: 'dueDate' },
      { label: 'Priority', html: (r) => pill(r.priority, r.priority === 'HIGH' ? 'bad' : r.priority === 'MEDIUM' ? 'warn' : 'info') },
    ], notes.actionItems)}`;
}

/* ===================================================================== 7 mcp */

pages.mcp = {
  init() {
    exampleButtons($('#mcpExamples'), samples.mcp || [], (s) => { $('#mcpInput').value = s.text; askMcp(); });
    $('#mcpForm').addEventListener('submit', (e) => { e.preventDefault(); askMcp(); });
    $('#tripForm').addEventListener('submit', (e) => { e.preventDefault(); tripBriefing(); });
    $('#mcpLoadCatalog').addEventListener('click', loadCatalog);
    const t = samples.tripBriefing || {};
    $('#tripCity').value = t.city || '';
    $('#tripDate').value = t.date || '';
    $('#tripBudget').value = t.budget || '';
    loadCatalog();
  },
};

async function loadCatalog() {
  try {
    const { data } = await api('/api/mcp/catalog');
    const list = (items) => `<ul>${items.map((i) => `<li><code>${esc(i.name)}</code> — ${esc(i.description)}</li>`).join('')}</ul>`;
    $('#mcpCatalog').innerHTML = `<div class="panel ok">${pill('connected', 'ok')} <b>${esc(data.server)}</b>
      <h3>Tools</h3>${list(data.tools)}<h3>Resources</h3>${list(data.resources)}
      <button class="small ghost" id="readFx">Read travel://fx-rates</button><div id="fxOut"></div>
      <h3>Prompts</h3>${list(data.prompts)}</div>`;
    $('#readFx').addEventListener('click', async () => {
      const { data: text } = await api('/api/mcp/resource?uri=' + encodeURIComponent('travel://fx-rates'));
      $('#fxOut').innerHTML = `<pre class="code">${esc(text)}</pre>`;
    });
  } catch (e) {
    $('#mcpCatalog').innerHTML = problemHtml(e) + '<button class="ghost" id="mcpRetry">Retry</button>';
    $('#mcpRetry').addEventListener('click', loadCatalog);
  }
}

function travelAnswerHtml(data, label) {
  return `<div class="panel ok"><div class="meta">${esc(label)}</div>
    ${data.promptFromServer ? `<h3>Prompt fetched from the MCP server</h3><div class="seen">${esc(data.promptFromServer)}</div>` : ''}
    <h3>Answer</h3>${md(data.answer)}
    <h3>Remote tool calls (executed on the MCP server)</h3>${traceTable(data.toolTrace)}</div>`;
}

async function askMcp() {
  const question = $('#mcpInput').value.trim();
  if (!question) return;
  loading($('#mcpOut'), 'Calling the model with MCP tools…');
  try {
    const { data } = await busy($('#mcpForm button'), 'Asking', () => api('/api/mcp/ask', { method: 'POST', body: { question } }));
    $('#mcpOut').innerHTML = travelAnswerHtml(data, 'Q: ' + question);
  } catch (e) { $('#mcpOut').innerHTML = problemHtml(e); }
}

async function tripBriefing() {
  const body = { city: $('#tripCity').value.trim(), date: $('#tripDate').value.trim(), budget: $('#tripBudget').value.trim() };
  loading($('#mcpOut'), 'Fetching the trip-briefing prompt from the server and running it…');
  try {
    const { data } = await busy($('#tripForm button'), 'Preparing', () => api('/api/mcp/trip-briefing', { method: 'POST', body }));
    $('#mcpOut').innerHTML = travelAnswerHtml(data, `Trip briefing: ${body.city}, ${body.date}, ${body.budget}`);
  } catch (e) { $('#mcpOut').innerHTML = problemHtml(e); }
}

/* ===================================================================== 8 observability */

pages.observability = { init: () => { $('#obsRefresh').addEventListener('click', loadCounters); }, show: () => loadCounters() };

async function metric(name, ...tags) {
  const qs = tags.length ? '?' + tags.map((t) => 'tag=' + encodeURIComponent(t)).join('&') : '';
  try {
    const { data } = await api(`/actuator/metrics/${name}${qs}`);
    return data;
  } catch { return null; }
}
const stat = (m, s) => (m?.measurements || []).find((x) => x.statistic === s)?.value ?? 0;

async function loadCounters() {
  const out = $('#obsOut');
  loading(out, 'Reading Micrometer meters…');
  const timer = await metric('acme.ai.feature');
  const features = (timer?.availableTags || []).find((t) => t.tag === 'feature')?.values || [];
  const rows = await Promise.all(features.sort().map(async (f) => {
    const t = await metric('acme.ai.feature', 'feature:' + f);
    const input = stat(await metric('acme.ai.tokens', 'feature:' + f, 'type:input'), 'COUNT');
    const output = stat(await metric('acme.ai.tokens', 'feature:' + f, 'type:output'), 'COUNT');
    const count = stat(t, 'COUNT');
    const toMs = (t?.baseUnit || 'seconds').startsWith('milli') ? 1 : 1000;
    return { feature: f, count, avg: count ? Math.round(stat(t, 'TOTAL_TIME') / count * toMs) : 0, input, output };
  }));
  const guards = await metric('acme.guardrails.events');
  const guardTags = (guards?.availableTags || []).find((t) => t.tag === 'guard')?.values || [];
  const guardRows = await Promise.all(guardTags.map(async (g) => ({ guard: g, events: stat(await metric('acme.guardrails.events', 'guard:' + g), 'COUNT') })));
  out.innerHTML = `<h2>Since app start (from <code>/actuator/metrics</code>)</h2>
    ${table([
      { label: 'Feature', html: (r) => `<b>${esc(r.feature)}</b>` },
      { label: 'Requests', key: 'count' },
      { label: 'Avg latency', html: (r) => ms(r.avg) },
      { label: 'Input tokens', key: 'input' },
      { label: 'Output tokens', key: 'output' },
    ], rows, 'No AI calls yet — run a few demos first.')}
    <h2>Guardrail interventions</h2>
    ${table([{ label: 'Guard', key: 'guard' }, { label: 'Events', key: 'events' }], guardRows, 'No guardrail events yet.')}
    <p class="meta">Grafana shows the same data over time, plus traces: HTTP request → ChatClient → advisors → vector search → model call → tool calls.</p>`;
}

/* ===================================================================== 9 evaluation */

pages.evaluation = {
  init() {
    $('#evalRun').addEventListener('click', (e) => runEval(e.currentTarget, ''));
    $('#evalRegress').addEventListener('click', (e) => runEval(e.currentTarget, '?topK=1&similarityThreshold=0.95'));
  },
};

async function runEval(button, qs) {
  loading($('#evalOut'), 'Running the golden set through RAG and the LLM judge…');
  try {
    const { data } = await busy(button, 'Evaluating', () => api('/api/eval/rag' + qs, { method: 'POST' }));
    const yesNo = (v) => (v === null || v === undefined ? '<span class="meta">n/a</span>' : v ? pill('yes', 'ok') : pill('no', 'bad'));
    $('#evalOut').innerHTML = `<div class="panel ${data.gatePassed ? 'ok' : 'bad'}">
      <h3>${data.gatePassed ? pill('QUALITY GATE PASSED', 'ok') : pill('QUALITY GATE FAILED', 'bad')}
        ${data.passed}/${data.total} cases passed · pass rate ${(data.passRate * 100).toFixed(0)}% (minimum ${(data.minPassRate * 100).toFixed(0)}%)</h3>
      <p class="meta">Retrieval settings: top-k ${data.topK}, similarity threshold ${data.similarityThreshold}</p></div>
      ${table([
        { label: 'Question', key: 'question', cls: 'wrap' },
        { label: 'Answer', cls: 'wrap', html: (r) => esc(r.answer.length > 220 ? r.answer.slice(0, 220) + '…' : r.answer) },
        { label: 'Facts', html: (r) => yesNo(r.expectedFacts) },
        { label: 'Relevant', html: (r) => yesNo(r.relevant) },
        { label: 'Grounded', html: (r) => yesNo(r.grounded) },
        { label: 'Result', html: (r) => (r.pass ? pill('PASS', 'ok') : pill('FAIL', 'bad')) },
        { label: 'Cited right source', html: (r) => yesNo(r.citedCorrectly) },
      ], data.cases)}
      <p class="meta">Facts = deterministic keyword check · Relevant / Grounded = LLM-as-judge against the retrieved chunks ·
        "Cited right source" is informational: small models often answer correctly but cite the wrong [n].</p>`;
  } catch (e) { $('#evalOut').innerHTML = problemHtml(e); }
}

/* ===================================================================== boot */

(async function boot() {
  try { samples = await (await fetch('samples.json')).json(); } catch { samples = {}; }
  loadStatus();
  showPage(location.hash.slice(1) || 'home');
})();
