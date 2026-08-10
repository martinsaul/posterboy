// --- Tab navigation ---
document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
        tab.classList.add('active');
        document.getElementById('tab-' + tab.dataset.tab).classList.add('active');

        // Load data for the tab
        const loaders = {
            overview: loadOverview,
            plugins: loadPlugins,
            routing: loadRouting,
            smtp: loadSmtp,
            config: loadConfigYaml,
            approval: loadApproval,
            debug: loadDebug
        };
        if (loaders[tab.dataset.tab]) loaders[tab.dataset.tab]();
    });
});

// --- Toast ---
function toast(message, type = 'success') {
    const el = document.getElementById('toast');
    el.textContent = message;
    el.className = type + ' visible';
    setTimeout(() => { el.classList.remove('visible'); }, 3000);
}

// --- API helpers ---
async function api(path, opts = {}) {
    const res = await fetch('/api' + path, {
        headers: { 'Content-Type': 'application/json', ...opts.headers },
        ...opts
    });
    return res;
}

async function apiJson(path, opts = {}) {
    const res = await api(path, opts);
    return res.json();
}

// --- Overview ---
async function loadOverview() {
    try {
        const status = await apiJson('/status');
        const plugins = await apiJson('/plugins');
        const el = document.getElementById('overview-info');
        el.innerHTML = `
            <div class="overview-grid">
                <div class="card">
                    <h4>Status</h4>
                    <div class="value">${status.status}</div>
                </div>
                <div class="card">
                    <h4>SMTP Port</h4>
                    <div class="value">${status.smtp_port}</div>
                </div>
                <div class="card">
                    <h4>Admin Port</h4>
                    <div class="value">${status.admin_port}</div>
                </div>
                <div class="card">
                    <h4>Loaded Plugins</h4>
                    <div class="value">${plugins.map(p => p.id).join(', ') || 'none'}</div>
                </div>
            </div>
        `;
        const badge = document.getElementById('status-badge');
        badge.textContent = status.status;
        badge.className = status.status === 'running' ? 'running' : '';
    } catch (e) {
        document.getElementById('overview-info').textContent = 'Failed to load status: ' + e.message;
    }
}

// --- Plugins ---
let editingPluginId = null;

async function loadPlugins() {
    try {
        const plugins = await apiJson('/plugins');
        const el = document.getElementById('plugins-list');
        if (plugins.length === 0) {
            el.innerHTML = '<p class="hint">No plugins loaded.</p>';
            return;
        }
        el.innerHTML = plugins.map(p => {
            let statusLabel = '';
            let statusClass = p.configured ? '' : 'inactive';
            if (p.id === 'gmail' && p.gmail_status) {
                const labels = {
                    'authorized': 'authorized',
                    'smtp_mode': 'smtp mode',
                    'needs_auth': 'needs authorization',
                    'needs_credentials': 'needs setup',
                    'not_configured': 'not configured'
                };
                statusLabel = labels[p.gmail_status] || p.gmail_status;
                statusClass = p.gmail_status === 'authorized' || p.gmail_status === 'smtp_mode' ? '' : 'inactive';
            }
            const label = statusLabel ? `<span class="plugin-status-label">${statusLabel}</span>` : '';
            const setupBtn = p.id === 'gmail' && p.gmail_status && p.gmail_status !== 'smtp_mode'
                ? `<button onclick="event.stopPropagation(); openGmailSetup()">Setup</button>` : '';
            const toggleBtn = p.configured || p.enabled === false
                ? `<button class="secondary" onclick="event.stopPropagation(); togglePlugin('${p.id}', ${!p.enabled})">${p.enabled ? 'Disable' : 'Enable'}</button>` : '';
            return `
                <div class="plugin-card${p.enabled === false ? ' disabled' : ''}" onclick="editPlugin('${p.id}')">
                    <div class="plugin-info">
                        <div class="plugin-status ${statusClass}"></div>
                        <span class="plugin-name">${p.name}</span>
                        <span class="plugin-id">${p.id}</span>
                        ${label}
                    </div>
                    <div class="plugin-actions">
                        ${setupBtn}
                        ${toggleBtn}
                        <button onclick="event.stopPropagation(); editPlugin('${p.id}')">Configure</button>
                    </div>
                </div>
            `;
        }).join('');
    } catch (e) {
        document.getElementById('plugins-list').innerHTML = '<p>Failed to load plugins: ' + e.message + '</p>';
    }
}

async function editPlugin(id) {
    editingPluginId = id;
    document.getElementById('plugin-editor-name').textContent = id;
    try {
        const res = await api('/plugins/' + id + '/config');
        let configText;
        if (res.ok) {
            const config = await res.json();
            configText = JSON.stringify(config, null, 2);
        } else {
            configText = '{\n  \n}';
        }
        document.getElementById('plugin-config-editor').value = configText;
        document.getElementById('plugin-editor').classList.remove('hidden');
    } catch (e) {
        toast('Failed to load plugin config: ' + e.message, 'error');
    }
}

function cancelPluginEdit() {
    editingPluginId = null;
    document.getElementById('plugin-editor').classList.add('hidden');
}

async function savePluginConfig() {
    if (!editingPluginId) return;
    const raw = document.getElementById('plugin-config-editor').value;
    try {
        JSON.parse(raw); // validate
    } catch (e) {
        toast('Invalid JSON: ' + e.message, 'error');
        return;
    }
    try {
        const res = await apiJson('/plugins/' + editingPluginId + '/config', {
            method: 'PUT',
            body: raw
        });
        if (res.status === 'ok') {
            toast(res.message);
            cancelPluginEdit();
            loadPlugins();
        } else {
            toast(res.message, 'error');
        }
    } catch (e) {
        toast('Failed to save: ' + e.message, 'error');
    }
}

// --- Routing ---
async function loadRouting() {
    try {
        const routing = await apiJson('/routing');
        document.getElementById('routing-editor').value = JSON.stringify(routing, null, 2);
    } catch (e) {
        toast('Failed to load routing: ' + e.message, 'error');
    }
}

async function saveRouting() {
    const raw = document.getElementById('routing-editor').value;
    try {
        JSON.parse(raw);
    } catch (e) {
        toast('Invalid JSON: ' + e.message, 'error');
        return;
    }
    try {
        const res = await apiJson('/routing', { method: 'PUT', body: raw });
        if (res.status === 'ok') {
            toast(res.message);
        } else {
            toast(res.message, 'error');
        }
    } catch (e) {
        toast('Failed to save routing: ' + e.message, 'error');
    }
}

// --- SMTP ---
async function loadSmtp() {
    try {
        const smtp = await apiJson('/smtp');
        document.getElementById('smtp-port').value = smtp.port;
        document.getElementById('smtp-auth-enabled').checked = smtp.auth.enabled;
        document.getElementById('smtp-username').value = smtp.auth.username || '';
        document.getElementById('smtp-password').value = smtp.auth.password || '';
        toggleAuthFields();
    } catch (e) {
        toast('Failed to load SMTP config: ' + e.message, 'error');
    }
}

document.getElementById('smtp-auth-enabled').addEventListener('change', toggleAuthFields);

function toggleAuthFields() {
    const enabled = document.getElementById('smtp-auth-enabled').checked;
    document.getElementById('smtp-auth-fields').style.display = enabled ? 'block' : 'none';
}

async function saveSmtp(event) {
    event.preventDefault();
    const authEnabled = document.getElementById('smtp-auth-enabled').checked;
    const config = {
        port: parseInt(document.getElementById('smtp-port').value),
        auth: {
            enabled: authEnabled,
            username: authEnabled ? document.getElementById('smtp-username').value : '',
            password: authEnabled ? document.getElementById('smtp-password').value : ''
        }
    };
    try {
        const res = await apiJson('/smtp', {
            method: 'PUT',
            body: JSON.stringify(config)
        });
        if (res.status === 'ok') {
            toast(res.message);
        } else {
            toast(res.message, 'error');
        }
    } catch (e) {
        toast('Failed to save SMTP config: ' + e.message, 'error');
    }
}

// --- Config (raw YAML) ---
async function loadConfigYaml() {
    try {
        const res = await fetch('/api/config/yaml');
        const yaml = await res.text();
        document.getElementById('config-editor').value = yaml;
    } catch (e) {
        toast('Failed to load config: ' + e.message, 'error');
    }
}

async function importConfig() {
    const yaml = document.getElementById('config-editor').value;
    if (!yaml.trim()) {
        toast('Config is empty', 'error');
        return;
    }
    try {
        const res = await apiJson('/config/import', {
            method: 'POST',
            body: yaml,
            headers: { 'Content-Type': 'text/plain' }
        });
        if (res.status === 'ok') {
            toast(res.message);
            loadOverview();
        } else {
            toast(res.message, 'error');
        }
    } catch (e) {
        toast('Failed to import config: ' + e.message, 'error');
    }
}

function exportConfig() {
    window.location.href = '/api/config/export';
}

// --- Plugin enable/disable ---

async function togglePlugin(id, enable) {
    try {
        const action = enable ? 'enable' : 'disable';
        const res = await apiJson('/plugins/' + id + '/' + action, { method: 'POST' });
        if (res.status === 'ok') {
            toast(res.message);
            loadPlugins();
        } else {
            toast(res.message, 'error');
        }
    } catch (e) {
        toast('Failed: ' + e.message, 'error');
    }
}

// --- Gmail Setup ---

async function openGmailSetup() {
    document.getElementById('gmail-setup').classList.remove('hidden');
    document.getElementById('plugin-editor').classList.add('hidden');
    await refreshGmailStatus();
}

function closeGmailSetup() {
    document.getElementById('gmail-setup').classList.add('hidden');
    loadPlugins();
}

async function refreshGmailStatus() {
    try {
        const status = await apiJson('/gmail/status');
        const card = document.getElementById('gmail-status-card');
        const stepCred = document.getElementById('gmail-step-credentials');
        const stepAuth = document.getElementById('gmail-step-auth');
        const stepDone = document.getElementById('gmail-step-done');

        stepCred.classList.add('hidden');
        stepAuth.classList.add('hidden');
        stepDone.classList.add('hidden');

        if (status.status === 'not_configured') {
            card.innerHTML = '<p>Gmail plugin is not configured. Go to <b>Configure</b> and set strategy to "api" with credentials_path and tokens_path, then return here.</p>';
        } else if (status.status === 'smtp_mode') {
            card.innerHTML = '<p>Gmail is using SMTP mode (app password). No OAuth setup needed.</p>';
        } else if (status.status === 'needs_credentials') {
            card.innerHTML = '<p>GCP OAuth credentials needed.</p>';
            stepCred.classList.remove('hidden');
        } else if (status.status === 'needs_auth') {
            card.innerHTML = '<p>Credentials uploaded. Click below to authorize with Google.</p>';
            stepCred.classList.remove('hidden');
            stepAuth.classList.remove('hidden');
        } else if (status.status === 'authorized') {
            card.innerHTML = '<p style="color:var(--success)">Gmail API is authorized and ready to send.</p>';
            stepDone.classList.remove('hidden');
            loadGmailAliases();
        }
    } catch (e) {
        toast('Failed to check Gmail status: ' + e.message, 'error');
    }
}

async function submitGmailCredentials() {
    const text = document.getElementById('gmail-cred-paste').value.trim();
    if (!text) { toast('Paste credentials JSON first', 'error'); return; }
    try {
        JSON.parse(text);
    } catch (err) {
        toast('Invalid JSON: ' + err.message, 'error');
        return;
    }
    try {
        const res = await apiJson('/gmail/credentials', {
            method: 'POST',
            body: text
        });
        if (res.status === 'ok') {
            toast(res.message);
            await refreshGmailStatus();
        } else {
            toast(res.message, 'error');
        }
    } catch (err) {
        toast('Failed to save credentials: ' + err.message, 'error');
    }
}

async function startGmailAuth() {
    try {
        const res = await apiJson('/gmail/auth-url');
        if (res.auth_url) {
            window.location.href = res.auth_url;
        } else {
            toast('Failed to get auth URL', 'error');
        }
    } catch (e) {
        toast('Failed to start auth: ' + e.message, 'error');
    }
}

// --- Gmail Aliases ---

async function loadGmailAliases() {
    try {
        const aliases = await apiJson('/gmail/aliases');
        const select = document.getElementById('gmail-alias-select');
        // Get current default alias from config
        const configRes = await api('/plugins/gmail/config');
        let currentAlias = '';
        if (configRes.ok) {
            const cfg = await configRes.json();
            currentAlias = cfg.default_alias || '';
        }
        select.innerHTML = aliases.map(a => {
            const label = a.displayName ? `${a.displayName} <${a.email}>` : a.email;
            const primary = a.isPrimary === 'true' ? ' (primary)' : '';
            const selected = a.email === currentAlias ? ' selected' : '';
            return `<option value="${a.email}"${selected}>${label}${primary}</option>`;
        }).join('');
    } catch (e) {
        document.getElementById('gmail-alias-picker').innerHTML = '<p class="hint">Could not load aliases: ' + e.message + '</p>';
    }
}

async function saveGmailAlias() {
    const alias = document.getElementById('gmail-alias-select').value;
    if (!alias) return;
    try {
        const res = await apiJson('/gmail/alias', {
            method: 'POST',
            body: JSON.stringify({ alias })
        });
        if (res.status === 'ok') toast(res.message);
        else toast(res.message, 'error');
    } catch (e) {
        toast('Failed: ' + e.message, 'error');
    }
}

// --- Debug Monitor ---

let debugWs = null;
let debugEntries = [];

function loadDebug() {
    updateDebugUI();
}

function toggleDebug() {
    if (debugWs && debugWs.readyState === WebSocket.OPEN) {
        debugWs.close();
    } else {
        connectDebugWs();
    }
}

function connectDebugWs() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    debugWs = new WebSocket(proto + '//' + location.host + '/ws/debug');

    debugWs.onopen = () => {
        updateDebugUI();
        toast('Debug monitor connected');
    };

    debugWs.onmessage = (event) => {
        try {
            const entry = JSON.parse(event.data);
            debugEntries.unshift(entry);
            if (debugEntries.length > 200) debugEntries.length = 200;
            renderDebugEntry(entry, true);
            document.getElementById('debug-count').textContent = debugEntries.length + ' entries';
        } catch (e) {
            console.error('Failed to parse WS message:', e, event.data);
        }
    };

    debugWs.onclose = (event) => {
        console.log('WS closed:', event.code, event.reason);
        updateDebugUI();
    };

    debugWs.onerror = (event) => {
        console.error('WS error:', event);
        toast('Debug WebSocket error', 'error');
    };
}

function updateDebugUI() {
    const connected = debugWs && debugWs.readyState === WebSocket.OPEN;
    document.getElementById('debug-status-text').textContent = connected ? 'Connected (live)' : 'Disconnected';
    document.getElementById('debug-status-text').style.color = connected ? 'var(--success)' : 'var(--text-muted)';
    document.getElementById('debug-toggle').textContent = connected ? 'Disconnect' : 'Connect';
    document.getElementById('debug-count').textContent = debugEntries.length ? debugEntries.length + ' entries' : '';
}

function renderDebugEntry(entry, prepend) {
    const feed = document.getElementById('debug-feed');
    const time = new Date(entry.timestamp).toLocaleTimeString();
    const routes = entry.routes.map(r => r.plugin_id).join(', ');
    const div = document.createElement('div');
    div.className = 'debug-entry';
    div.onclick = () => showDebugDetail(entry);
    div.innerHTML = `
        <span class="debug-time">${time}</span>
        <span class="debug-route">${routes}</span>
        <span class="debug-subject">${escapeHtml(entry.subject)}</span>
        <span class="hint">${escapeHtml(entry.original_from)}</span>
    `;
    if (prepend) feed.prepend(div);
    else feed.appendChild(div);
}

function showDebugDetail(entry) {
    const time = new Date(entry.timestamp).toLocaleString();
    const routes = entry.routes.map(r =>
        `${r.recipient} -> ${r.plugin_id}${r.rule_name ? ' (rule: ' + r.rule_name + ')' : ' (default)'}`
    ).join('\n');

    document.getElementById('debug-modal-body').innerHTML = `
        <div class="detail-row"><span class="detail-label">Time</span><span class="detail-value">${time}</span></div>
        <div class="detail-row"><span class="detail-label">From</span><span class="detail-value">${escapeHtml(entry.original_from)}</span></div>
        <div class="detail-row"><span class="detail-label">To</span><span class="detail-value">${escapeHtml(entry.original_to.join(', '))}</span></div>
        <div class="detail-row"><span class="detail-label">Subject</span><span class="detail-value">${escapeHtml(entry.subject)}</span></div>
        <div class="detail-row"><span class="detail-label">Attachments</span><span class="detail-value">${entry.attachment_count}</span></div>
        <div class="detail-row"><span class="detail-label">Routing</span></div>
        <div class="detail-body">${escapeHtml(routes)}</div>
        <div class="detail-row" style="margin-top:0.75rem"><span class="detail-label">Body Preview</span></div>
        <div class="detail-body">${escapeHtml(entry.body_preview)}</div>
    `;
    document.getElementById('debug-modal').classList.remove('hidden');
}

function closeDebugModal() {
    document.getElementById('debug-modal').classList.add('hidden');
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

// --- Approval Queue ---
let selectedApprovalId = null;

async function loadApproval() {
    try {
        const items = await apiJson('/approval/pending');
        const el = document.getElementById('approval-list');
        if (items.length === 0) {
            el.innerHTML = '<div class="card"><p class="hint">No messages pending approval.</p></div>';
            return;
        }
        el.innerHTML = items.map(m => `
            <div class="debug-entry" onclick="showApprovalDetail('${escapeHtml(m.id)}')">
                <span class="debug-time">${new Date(m.created_at).toLocaleString()}</span>
                <span class="debug-subject">${escapeHtml(m.subject)}</span>
                <br><span class="hint">${escapeHtml(m.from)} -> ${escapeHtml(m.recipients.join(', '))}</span>
            </div>
        `).join('');
    } catch (e) {
        document.getElementById('approval-list').innerHTML =
            '<div class="card"><p>Failed to load: ' + escapeHtml(e.message) + '</p></div>';
    }
}

function formatBytes(bytes) {
    if (bytes >= 1073741824) return (bytes / 1073741824).toFixed(1) + ' GB';
    if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + ' MB';
    if (bytes >= 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return bytes + ' B';
}

async function showApprovalDetail(id) {
    selectedApprovalId = id;
    try {
        const msg = await apiJson('/approval/' + id);
        document.getElementById('approval-modal-body').innerHTML = `
            <div class="detail-row"><span class="detail-label">From</span><span class="detail-value">${escapeHtml(msg.from)}</span></div>
            <div class="detail-row"><span class="detail-label">To</span><span class="detail-value">${escapeHtml(msg.recipients.join(', '))}</span></div>
            <div class="detail-row"><span class="detail-label">Subject</span><span class="detail-value">${escapeHtml(msg.subject)}</span></div>
            <div class="detail-row"><span class="detail-label">Received</span><span class="detail-value">${new Date(msg.created_at).toLocaleString()}</span></div>
        `;

        // Attachments
        const attEl = document.getElementById('approval-attachments');
        if (msg.attachments && msg.attachments.length > 0) {
            attEl.innerHTML = `
                <div style="margin-top:0.75rem"><span class="detail-label">Attachments (${msg.attachments.length})</span></div>
                <div style="margin-top:0.5rem;display:flex;flex-direction:column;gap:0.35rem">
                    ${msg.attachments.map(att => `
                        <a href="/api/approval/${encodeURIComponent(id)}/attachments/${att.index}"
                           download="${escapeHtml(att.filename)}"
                           style="display:inline-flex;align-items:center;gap:0.5rem;padding:0.4rem 0.75rem;background:var(--bg-secondary, #1a1a2e);border:1px solid var(--border, #333);border-radius:4px;color:var(--text-primary, #e0e0e0);text-decoration:none;font-size:0.85rem;width:fit-content">
                            <span style="font-weight:500">${escapeHtml(att.filename)}</span>
                            <span class="hint">${escapeHtml(att.mime_type)} &middot; ${formatBytes(att.size_bytes)}</span>
                        </a>
                    `).join('')}
                </div>
            `;
        } else {
            attEl.innerHTML = '';
        }

        // Full email body
        const bodyEl = document.getElementById('approval-body-content');
        const bodyText = msg.body_plain || msg.body_html || '(empty)';
        bodyEl.innerHTML = `
            <div style="margin-top:0.75rem"><span class="detail-label">Email Body</span></div>
            <pre style="margin-top:0.5rem;white-space:pre-wrap;font-size:0.85rem;color:var(--text-secondary, #aaa);max-height:400px;overflow-y:auto;padding:0.75rem;background:var(--bg-secondary, #1a1a2e);border:1px solid var(--border, #333);border-radius:4px">${escapeHtml(bodyText)}</pre>
        `;

        document.getElementById('approval-modal').classList.remove('hidden');
    } catch (e) {
        toast('Failed to load message: ' + e.message, 'error');
    }
}

function closeApprovalModal() {
    selectedApprovalId = null;
    document.getElementById('approval-modal').classList.add('hidden');
}

async function approveMessage() {
    if (!selectedApprovalId) return;
    try {
        const res = await apiJson('/approval/' + selectedApprovalId + '/approve', { method: 'POST' });
        toast(res.message || 'Message approved and delivered');
        closeApprovalModal();
        loadApproval();
    } catch (e) {
        toast('Failed: ' + e.message, 'error');
    }
}

async function dismissMessage() {
    if (!selectedApprovalId) return;
    try {
        const res = await apiJson('/approval/' + selectedApprovalId + '/dismiss', { method: 'POST' });
        toast(res.message || 'Message dismissed');
        closeApprovalModal();
        loadApproval();
    } catch (e) {
        toast('Failed: ' + e.message, 'error');
    }
}

// --- Init ---
loadOverview();
