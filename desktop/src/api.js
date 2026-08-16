const BASE = window.__TAURI__ ? 'http://localhost:8081/api/v1' : '/api/v1';
let currentSessionId = null;

/** 当前登录用户；未登录用 desktop-user */
export function getUserId() {
  return localStorage.getItem('userId') || 'desktop-user';
}

/** 登录成功后写入稳定 userId（UUID） */
export function setUserId(id) {
  const v = String(id || '').trim();
  if (!v) return;
  localStorage.setItem('userId', v);
  loadCurrentSessionId();
}

/**
 * 登录：写入 userId，并把访客当前会话带入该用户本地列表。
 * 后端会在下次发消息时 ensureDesktopSession 把 session 绑到真实 userId。
 * @returns {string|null} 带入后的当前 sessionId
 */
export function loginAs(userId) {
  const v = String(userId || '').trim();
  if (!v) return null;

  const guestSid =
    currentSessionId || localStorage.getItem('currentSessionId') || null;
  let guestEntry = null;
  try {
    const guestList = JSON.parse(localStorage.getItem('sessions') || '[]');
    guestEntry = guestList.find(s => s.id === guestSid) || null;
  } catch { /* ignore */ }
  if (!guestEntry && guestSid) {
    guestEntry = { id: guestSid, title: '新对话', time: Date.now() };
  }

  localStorage.setItem('userId', v);
  loadCurrentSessionId();

  if (guestEntry) {
    const list = getSessions();
    if (!list.some(s => s.id === guestEntry.id)) {
      list.unshift({
        id: guestEntry.id,
        title: guestEntry.title || '新对话',
        time: guestEntry.time || Date.now(),
        ...(guestEntry.pinned
          ? { pinned: true, pinTime: guestEntry.pinTime || Date.now() }
          : {}),
      });
      saveSessions(list);
    }
    switchSession(guestEntry.id);
  }
  return guestEntry?.id || currentSessionId;
}

/** 退出登录：清除账号，后续请求回退为 desktop-user */
export function clearUserId() {
  localStorage.removeItem('userId');
  localStorage.removeItem('userEmail');
  loadCurrentSessionId();
}

export function isLoggedIn() {
  const id = localStorage.getItem('userId');
  return !!(id && id !== 'desktop-user');
}

/**
 * 展示名：用户 + 8 位数字。由 userId 派生，不落库、不单独持久化。
 */
export function getDisplayName(userId = getUserId()) {
  const id = String(userId || '');
  let h = 2166136261;
  for (let i = 0; i < id.length; i += 1) {
    h ^= id.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  const n = Math.abs(h >>> 0) % 100000000;
  return `用户${String(n).padStart(8, '0')}`;
}

/** 会话列表 / 当前会话按登录用户隔离存储 */
function sessionsKey() {
  return isLoggedIn() ? `sessions:${getUserId()}` : 'sessions';
}

function currentSessionKey() {
  return isLoggedIn() ? `currentSessionId:${getUserId()}` : 'currentSessionId';
}

export function loadCurrentSessionId() {
  currentSessionId = localStorage.getItem(currentSessionKey()) || null;
  return currentSessionId;
}

loadCurrentSessionId();

function getSessions() {
  try { return JSON.parse(localStorage.getItem(sessionsKey()) || '[]'); }
  catch { return []; }
}
function saveSessions(list) {
  localStorage.setItem(sessionsKey(), JSON.stringify(list));
}

function parseSessionTime(s) {
  if (!s) return 0;
  const t = Date.parse(String(s).replace(' ', 'T'));
  return Number.isFinite(t) ? t : 0;
}

/** 置顶在前，其余按最近活动时间（置顶仅本地，不落库） */
function sortSessions(list) {
  return [...list].sort((a, b) => {
    const ap = a.pinned ? 1 : 0;
    const bp = b.pinned ? 1 : 0;
    if (ap !== bp) return bp - ap;
    if (ap && bp) return (b.pinTime || b.time || 0) - (a.pinTime || a.time || 0);
    return (b.time || 0) - (a.time || 0);
  });
}

function touchSessionTitle(fallback) {
  const list = getSessions();
  const s = list.find(x => x.id === currentSessionId);
  if (s && s.title === '新对话') {
    const t = (fallback || '图片消息').slice(0, 30);
    s.title = t;
    s.time = Date.now();
    saveSessions(list);
    if (isLoggedIn()) {
      updateSessionTitleRemote(currentSessionId, t).catch(() => {});
    }
  }
}

export function getSessionList() { return sortSessions(getSessions()); }

/**
 * 从后端拉取当前登录用户会话列表，写入本机按 userId 隔离的缓存。
 * 未登录时仅返回本地 guest 列表。
 */
export async function refreshSessionsFromServer() {
  if (!isLoggedIn()) {
    return getSessionList();
  }
  const res = await fetch(
    `${BASE}/session/list?userId=${encodeURIComponent(getUserId())}`
  );
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.code !== 200) {
    throw new Error(data.message || `拉取会话失败: ${res.status}`);
  }
  const remote = (data.data || []).map(s => ({
    id: s.sessionId,
    title: (s.title && String(s.title).trim()) || '新对话',
    time: parseSessionTime(s.lastMessageAt || s.updatedAt || s.createdAt) || Date.now(),
  }));
  const local = getSessions();
  // 保留尚未出现在服务端的本地会话（访客登录带入、空草稿等），避免刷新后丢掉当前对话
  const localsOnly = local.filter(
    s => s?.id && !remote.some(r => r.id === s.id)
  );
  // 保留本地置顶标记（不落库）
  const pinMap = new Map(local.filter(s => s.pinned).map(s => [s.id, s]));
  const merged = remote.map(r => {
    const p = pinMap.get(r.id);
    return p ? { ...r, pinned: true, pinTime: p.pinTime } : r;
  });
  const list = sortSessions([...localsOnly, ...merged]);
  saveSessions(list);
  if (currentSessionId && !list.some(s => s.id === currentSessionId) && list[0]) {
    // 当前 id 不在列表时不强制切换；由 UI 决定
  } else if (!currentSessionId && list[0]) {
    switchSession(list[0].id);
  }
  return list;
}

async function updateSessionTitleRemote(sessionId, title) {
  if (!sessionId || !title) return;
  await fetch(`${BASE}/session/${encodeURIComponent(sessionId)}/title`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  });
}

async function deleteSessionRemote(sessionId) {
  if (!sessionId) return;
  await fetch(`${BASE}/session/${encodeURIComponent(sessionId)}/delete`, {
    method: 'POST',
  });
}

/** 重命名会话；空标题忽略。已登录时同步后端。 */
export function renameSession(id, title) {
  const list = getSessions();
  const s = list.find(x => x.id === id);
  if (!s) return false;
  const t = String(title || '').trim().slice(0, 30);
  if (!t) return false;
  s.title = t;
  saveSessions(list);
  if (isLoggedIn()) {
    updateSessionTitleRemote(id, t).catch(() => {});
  }
  return true;
}

/** 置顶 / 取消置顶（仅本机，不落库） */
export function togglePinSession(id) {
  const list = getSessions();
  const s = list.find(x => x.id === id);
  if (!s) return false;
  if (s.pinned) {
    s.pinned = false;
    delete s.pinTime;
  } else {
    s.pinned = true;
    s.pinTime = Date.now();
  }
  saveSessions(list);
  return true;
}

export function getCurrentSessionId() { return currentSessionId; }

export function switchSession(id) {
  currentSessionId = id;
  localStorage.setItem(currentSessionKey(), id || '');
}

/**
 * 确保侧栏有该会话（提醒可能写到已知 id）。
 * @param {{ switchTo?: boolean }} opts switchTo 默认 true；系统提醒弹窗时可 false 避免强切会话
 */
export function ensureSession(id, title, opts = {}) {
  if (!id) return;
  const switchTo = opts.switchTo !== false;
  const list = getSessions();
  if (!list.some(s => s.id === id)) {
    list.unshift({ id, title: (title || '提醒').slice(0, 30), time: Date.now() });
    saveSessions(list);
  }
  if (switchTo) switchSession(id);
}

export function newSession() {
  const list = getSessions();
  // 仅复用「标题仍是新对话且本地无消息」的空会话，避免点进已有内容的同名会话
  const empty = list.find((s) => {
    if (!s || s.title !== '新对话') return false;
    const msgs = getCachedSessionMessages(s.id);
    return !Array.isArray(msgs) || msgs.length === 0;
  });
  if (empty) {
    currentSessionId = empty.id;
    localStorage.setItem(currentSessionKey(), empty.id);
    return empty.id;
  }

  const id = crypto.randomUUID();
  currentSessionId = id;
  localStorage.setItem(currentSessionKey(), id);
  list.unshift({ id, title: '新对话', time: Date.now() });
  saveSessions(list);
  return id;
}

/**
 * 退出登录后：清空访客本地会话与消息缓存，只留一个空的「新对话」。
 * 须在 clearUserId() 之后调用；不删服务器上登录用户的会话。
 * @returns {string} 新会话 id
 */
export function resetGuestWorkspace() {
  const old = getSessions();
  const cache = readMsgCache();
  for (const s of old) {
    if (s?.id && cache[s.id]) delete cache[s.id];
  }
  writeMsgCache(cache);
  saveSessions([]);
  currentSessionId = null;
  localStorage.setItem(currentSessionKey(), '');
  return newSession();
}

export async function sendMessage(message) {
  if (!currentSessionId) newSession();
  return sendMessageToSession(currentSessionId, message);
}

/**
 * 向指定会话发消息（不切换 currentSessionId）。
 * 用于右下角确认卡片：确认/取消挂起的 WRITE。
 */
export async function sendMessageToSession(sessionId, message) {
  if (!sessionId) throw new Error('sessionId 无效');
  const res = await fetch(`${BASE}/chat/send`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, userId: getUserId(), message, messageType: 'TEXT' })
  });
  if (!res.ok) throw new Error(`请求失败: ${res.status}`);
  const data = await res.json();
  if (data.code !== 200) throw new Error(data.message || '请求失败');
  // 仅当操作的是当前会话时才刷新侧栏标题
  if (sessionId === currentSessionId) {
    touchSessionTitle(message);
  }
  return {
    reply: data.data?.replyMessage || '出错',
    messageId: data.data?.messageId,
    sessionId: data.data?.sessionId || sessionId,
    finishReason: data.data?.finishReason || 'STOP',
    confirmThreadId: data.data?.confirmThreadId || null,
    attachments: normalizeAttachments(data.data?.attachments)
  };
}

/**
 * 解析 SSE 文本块：event + data 行。
 */
function parseSseChunk(buffer) {
  const events = [];
  const parts = buffer.split('\n\n');
  const rest = parts.pop() ?? '';
  for (const part of parts) {
    if (!part.trim()) continue;
    let event = 'message';
    const dataLines = [];
    for (const line of part.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
    }
    if (!dataLines.length) continue;
    const raw = dataLines.join('\n');
    let data = raw;
    try { data = JSON.parse(raw); } catch { /* keep string */ }
    events.push({ event, data });
  }
  return { events, rest };
}

/**
 * 消费 SSE 响应，回调 meta / progress / delta / done / error。
 */
async function consumeSse(res, { onMeta, onProgress, onDelta, onDone, signal } = {}) {
  if (!res.ok) {
    let msg = `请求失败: ${res.status}`;
    try {
      const j = await res.json();
      if (j?.message) msg = j.message;
    } catch { /* ignore */ }
    throw new Error(msg);
  }
  if (!res.body) throw new Error('浏览器不支持流式读取');

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  let meta = null;
  let finishReason = 'STOP';
  let fullText = '';

  while (true) {
    if (signal?.aborted) {
      try { await reader.cancel(); } catch { /* ignore */ }
      throw new DOMException('Aborted', 'AbortError');
    }
    const { done, value } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    const parsed = parseSseChunk(buf);
    buf = parsed.rest;
    for (const ev of parsed.events) {
      if (ev.event === 'meta') {
        meta = ev.data;
        finishReason = ev.data?.finishReason || finishReason;
        onMeta?.(ev.data);
      } else if (ev.event === 'progress') {
        const t = ev.data?.text;
        if (t) onProgress?.(t);
      } else if (ev.event === 'delta') {
        const t = ev.data?.text ?? '';
        fullText += t;
        onDelta?.(t, fullText);
      } else if (ev.event === 'done') {
        finishReason = ev.data?.finishReason || finishReason;
        onDone?.(ev.data);
      } else if (ev.event === 'error') {
        throw new Error(ev.data?.message || '流式错误');
      }
    }
  }

  return {
    reply: fullText,
    messageId: meta?.messageId,
    finishReason,
    confirmThreadId: meta?.confirmThreadId || null,
    fileId: meta?.fileId || '',
    fileName: meta?.fileName || '',
    fileStatus: meta?.fileStatus || '',
    attachments: normalizeAttachments(meta?.attachments),
    route: meta?.route || pickRouteAtt(meta?.attachments),
  };
}

function pickRouteAtt(attachments) {
  if (!Array.isArray(attachments)) return null;
  return attachments.find((a) => a && a.type === 'route') || null;
}

/** 把相对 /api 附件 URL 补成 Tauri/开发可用的绝对地址 */
export function resolveAttachmentUrl(url) {
  if (!url) return null;
  if (url.startsWith('http')) return url;
  const origin = window.__TAURI__ ? 'http://localhost:8081' : '';
  return origin + url;
}

function normalizeAttachments(list) {
  if (!Array.isArray(list)) return [];
  return list
    .filter(a => a && a.url)
    .map(a => ({
      type: a.type === 'image' ? 'image' : 'file',
      url: resolveAttachmentUrl(a.url),
      fileName: a.fileName || '附件'
    }));
}

const TEXT_PREVIEW_EXTS = ['.txt', '.md', '.json', '.csv', '.log', '.xml', '.html', '.htm', '.css', '.js', '.ts', '.jsx', '.tsx', '.yml', '.yaml'];

function isTextFileName(fileName) {
  if (!fileName) return false;
  const lower = fileName.toLowerCase();
  return TEXT_PREVIEW_EXTS.some(ext => lower.endsWith(ext));
}

/**
 * 打开附件：文本类拉取内容供应用内预览；其它类型触发本地下载。
 * Tauri 下 target=_blank 通常无效，必须走这条路径。
 */
export async function openAttachment(att) {
  if (!att?.url) throw new Error('附件地址无效');
  const url = resolveMediaUrl(att.url);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`打开失败 (${res.status})`);

  const fileName = att.fileName || '附件';
  const contentType = (res.headers.get('content-type') || '').toLowerCase();
  const asText = att.type !== 'image'
    && (contentType.startsWith('text/')
      || contentType.includes('json')
      || contentType.includes('xml')
      || contentType.includes('markdown')
      || isTextFileName(fileName));

  if (asText) {
    const text = await res.text();
    return { mode: 'text', fileName, text, url };
  }

  const blob = await res.blob();
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = objectUrl;
  a.download = fileName;
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(objectUrl), 30_000);
  return { mode: 'download', fileName, url };
}

/** 后端附件/媒体常返回 /api/v1/...；Tauri 下必须拼到 8081，否则会 404 */
export function resolveMediaUrl(mediaUrl) {
  if (!mediaUrl) return null;
  if (/^https?:\/\//i.test(mediaUrl)) return mediaUrl;
  if (window.__TAURI__) {
    return mediaUrl.startsWith('/')
      ? `http://localhost:8081${mediaUrl}`
      : `http://localhost:8081/${mediaUrl}`;
  }
  return mediaUrl.startsWith('/') ? mediaUrl : `/${mediaUrl}`;
}

/**
 * 文本流式：后端算完完整结果后再 SSE 分片。
 */
export async function sendMessageStream(message, handlers = {}) {
  if (!currentSessionId) newSession();
  touchSessionTitle(message);

  const res = await fetch(`${BASE}/chat/send-stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ sessionId: currentSessionId, userId: getUserId(), message, messageType: 'TEXT' }),
    signal: handlers.signal
  });
  return consumeSse(res, handlers);
}

/**
 * 发送图片（可选配文）→ POST /api/v1/chat/image
 */
export async function sendImage(file, message = '') {
  if (!currentSessionId) newSession();
  touchSessionTitle(message || '图片消息');

  const form = new FormData();
  form.append('file', file);
  form.append('sessionId', currentSessionId);
  form.append('userId', getUserId());
  if (message && message.trim()) {
    form.append('message', message.trim());
  }

  const res = await fetch(`${BASE}/chat/image`, {
    method: 'POST',
    body: form
  });
  let data = null;
  try { data = await res.json(); } catch { /* ignore */ }
  if (res.status === 404) {
    throw new Error('图片接口不存在(404)，请重启后端后再试');
  }
  if (!res.ok) {
    throw new Error(data?.message || `请求失败: ${res.status}`);
  }
  if (data?.code !== 200) throw new Error(data?.message || '请求失败');
  return {
    reply: data.data?.replyMessage || '出错',
    messageId: data.data?.messageId,
    imageDescription: data.data?.imageDescription || ''
  };
}

/**
 * 图片流式 → /chat/image-stream
 */
export async function sendImageStream(file, message = '', handlers = {}) {
  if (!currentSessionId) newSession();
  touchSessionTitle(message || '图片消息');

  const form = new FormData();
  form.append('file', file);
  form.append('sessionId', currentSessionId);
  form.append('userId', getUserId());
  if (message && message.trim()) {
    form.append('message', message.trim());
  }

  const res = await fetch(`${BASE}/chat/image-stream`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    body: form,
    signal: handlers.signal
  });
  if (res.status === 404) {
    throw new Error('图片流式接口不存在(404)，请重启后端后再试');
  }
  return consumeSse(res, handlers);
}

/**
 * 发送文档（可选配文）→ POST /api/v1/chat/file
 * 支持 pdf / doc / docx / txt，≤ 50MB
 */
export async function sendFile(file, message = '') {
  if (!currentSessionId) newSession();
  touchSessionTitle(message || file?.name || '文档消息');

  const form = new FormData();
  form.append('file', file);
  form.append('sessionId', currentSessionId);
  form.append('userId', getUserId());
  if (message && message.trim()) {
    form.append('message', message.trim());
  }

  const res = await fetch(`${BASE}/chat/file`, {
    method: 'POST',
    body: form
  });
  let data = null;
  try { data = await res.json(); } catch { /* ignore */ }
  if (res.status === 404) {
    throw new Error('文档接口不存在(404)，请重启后端后再试');
  }
  if (!res.ok) {
    throw new Error(data?.message || `请求失败: ${res.status}`);
  }
  if (data?.code !== 200) throw new Error(data?.message || '请求失败');
  return {
    reply: data.data?.replyMessage || '出错',
    messageId: data.data?.messageId,
    fileId: data.data?.fileId || '',
    fileName: data.data?.fileName || file.name,
    fileStatus: data.data?.fileStatus || ''
  };
}

/**
 * 文档流式 → /chat/file-stream
 */
export async function sendFileStream(file, message = '', handlers = {}) {
  if (!currentSessionId) newSession();
  touchSessionTitle(message || file?.name || '文档消息');

  const form = new FormData();
  form.append('file', file);
  form.append('sessionId', currentSessionId);
  form.append('userId', getUserId());
  if (message && message.trim()) {
    form.append('message', message.trim());
  }

  const res = await fetch(`${BASE}/chat/file-stream`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    body: form,
    signal: handlers.signal
  });
  if (res.status === 404) {
    throw new Error('文档流式接口不存在(404)，请重启后端后再试');
  }
  return consumeSse(res, handlers);
}

const MSG_CACHE_KEY = 'sessionMessagesCache';

function readMsgCache() {
  try { return JSON.parse(localStorage.getItem(MSG_CACHE_KEY) || '{}'); }
  catch { return {}; }
}

function writeMsgCache(all) {
  try { localStorage.setItem(MSG_CACHE_KEY, JSON.stringify(all)); }
  catch { /* quota */ }
}

/** 本地缓存当前会话消息（后端挂掉切会话时仍可渲染） */
export function cacheSessionMessages(sid, messages) {
  if (!sid || !Array.isArray(messages)) return;
  const slim = messages
    .filter(m => m && (m.role === 'user' || m.role === 'assistant') && !m.streaming)
    .map(m => ({
      role: m.role,
      text: m.role === 'assistant' ? stripAssistantMarkers(m.text || '') : (m.text || ''),
      messageType: m.messageType || 'TEXT',
      finishReason: m.finishReason || undefined,
      voice: m.voice || undefined,
      attachments: Array.isArray(m.attachments) ? m.attachments : undefined,
      // blob: 预览离开后失效，不缓存
      imageUrl: m.imageUrl && !String(m.imageUrl).startsWith('blob:') ? m.imageUrl : null,
      fileName: m.fileName || null
    }));
  const all = readMsgCache();
  all[sid] = slim;
  // 只保留侧栏里还在的会话，避免无限膨胀
  const keep = new Set(getSessions().map(s => s.id));
  keep.add(sid);
  for (const key of Object.keys(all)) {
    if (!keep.has(key)) delete all[key];
  }
  writeMsgCache(all);
}

export function getCachedSessionMessages(sid) {
  if (!sid) return [];
  const list = readMsgCache()[sid];
  return Array.isArray(list) ? list : [];
}

/** 是否像工具/Agent 中间态，不应展示给用户 */
function isToolNoiseMessage(raw, text) {
  const type = raw.messageType || 'TEXT';
  const role = String(raw.role || '');
  const finish = String(raw.finishReason || '');
  if (type === 'TOOL_CALL' || finish === 'TOOL') return true;
  if (role === 'TOOL' || role === 'tool') return true;
  const t = (text || '').trim();
  // 空助手气泡（常见于仅 tool_calls 的中间 assistant）
  if (!t && type !== 'IMAGE' && type !== 'FILE' && !raw.mediaUrl && !raw.fileName) {
    return role === 'ASSISTANT' || role === 'assistant';
  }
  if (
    t.includes('needsConfirm')
    || t.includes('"tool_calls"')
    || t.includes('"toolCalls"')
    || t.includes('FunctionCall')
    || /^\s*\{[\s\S]*"name"\s*:\s*"[a-zA-Z0-9_]+"/.test(t)
  ) {
    return true;
  }
  return false;
}

function mapHistoryRows(rows) {
  const mapped = (rows || []).map(m => {
    const type = m.messageType || 'TEXT';
    const hasAttachment = type === 'IMAGE' || type === 'FILE' || m.mediaUrl || m.fileName;
    const text = hasAttachment
      ? (m.displayText ?? '')
      : (m.displayText || m.content || '');
    if (isToolNoiseMessage(m, text)) return null;
    return {
      role: m.role === 'USER' || m.role === 'user' ? 'user' : 'assistant',
      text,
      messageType: type,
      finishReason: m.finishReason || null,
      imageUrl: resolveMediaUrl(m.mediaUrl),
      fileName: m.fileName || null
    };
  }).filter(Boolean);

  // 连续重复去重
  const deduped = [];
  for (const m of mapped) {
    const prev = deduped[deduped.length - 1];
    if (
      prev
      && prev.role === m.role
      && prev.text === m.text
      && prev.imageUrl === m.imageUrl
      && prev.fileName === m.fileName
    ) {
      continue;
    }
    deduped.push(m);
  }

  // 连续助手且前一条像工具中间态时，用后一条覆盖（避免盖掉「最终回复 + 提醒」）
  const isIntermediateAssistant = (m) => {
    if (!m || m.role !== 'assistant') return false;
    if (m.imageUrl || m.fileName) return false;
    const fr = m.finishReason;
    if (fr === 'STOP' || fr === 'NEED_CONFIRM' || fr === 'REMIND' || fr === 'ERROR') return false;
    const t = (m.text || '').trim();
    return !t || !fr || fr === 'TOOL';
  };
  const collapsed = [];
  for (const m of deduped) {
    const prev = collapsed[collapsed.length - 1];
    if (m.role === 'assistant' && isIntermediateAssistant(prev)) {
      collapsed[collapsed.length - 1] = m;
      continue;
    }
    collapsed.push(m);
  }
  return collapsed;
}

export async function getHistory(sid) {
  const id = sid || currentSessionId;
  if (!id) return [];
  const cached = getCachedSessionMessages(id);
  try {
    const res = await fetch(`${BASE}/chat/history?sessionId=${id}`);
    if (!res.ok) throw new Error(`history ${res.status}`);
    const data = await res.json();
    if (data.code !== 200) throw new Error(data.message || 'history failed');
    const list = mapHistoryRows(data.data);
    cacheSessionMessages(id, list);
    return list;
  } catch {
    // 后端关闭或网络失败：回退本地缓存，避免切会话变空白
    return cached;
  }
}

export function deleteSession(id) {
  const list = getSessions().filter(x => x.id !== id);
  saveSessions(list);
  const cache = readMsgCache();
  if (cache[id]) {
    delete cache[id];
    writeMsgCache(cache);
  }
  if (currentSessionId === id) {
    currentSessionId = list[0]?.id || null;
    localStorage.setItem(currentSessionKey(), currentSessionId || '');
  }
  if (isLoggedIn()) {
    deleteSessionRemote(id).catch(() => {});
  }
}

/** 拉取未读桌面通知（提醒等，不走微信） */
export async function pollNotifications() {
  const res = await fetch(`${BASE}/desktop/notifications?userId=${encodeURIComponent(getUserId())}&limit=20`);
  if (!res.ok) return [];
  const data = await res.json();
  if (data.code !== 200) return [];
  return data.data || [];
}

/** 桌面用量统计（token / 耗时 / 请求数，进程内） */
export async function fetchUsageStats(limit = 30) {
  const res = await fetch(
    `${BASE}/desktop/usage?userId=${encodeURIComponent(getUserId())}&limit=${encodeURIComponent(limit)}&_=${Date.now()}`,
    { cache: 'no-store' }
  );
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.code !== 200) {
    throw new Error(data.message || `用量查询失败 (${res.status})`);
  }
  return data.data || { summary: {}, recentTurns: [], note: '' };
}

/**
 * 思维链工作叙述（LLM 流式）
 * @param {{ userText?: string, phase: string, eventText?: string, recentLines?: string[], factsHint?: string }} body
 * @param {{ signal?: AbortSignal, onDelta?: (chunk: string, full: string) => void }} [opts]
 * @returns {Promise<string>} 清洗后全文
 */
export async function narrateReason(body, opts) {
  // 兼容旧调用：narrateReason(body, signal)
  const signal = opts instanceof AbortSignal ? opts : opts?.signal;
  const onDelta = opts instanceof AbortSignal ? undefined : opts?.onDelta;

  const res = await fetch(`${BASE}/desktop/reason/narrate-stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(body || {}),
    signal,
  });

  let finalText = '';
  const result = await consumeSse(res, {
    signal,
    onDelta: (chunk, full) => {
      finalText = full;
      onDelta?.(chunk, full);
    },
    onDone: (data) => {
      if (data?.text) finalText = String(data.text);
    },
  });

  const text = String(finalText || result.reply || '').trim();
  if (!text) {
    // 流失败时回退非流式
    const fallback = await fetch(`${BASE}/desktop/reason/narrate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {}),
      signal,
    });
    const data = await fallback.json().catch(() => ({}));
    if (!fallback.ok || data.code !== 200) {
      throw new Error(data.message || `独白生成失败 (${fallback.status})`);
    }
    return String(data.data?.text || '').trim();
  }
  return text;
}

/** 欢迎页资讯（标题+正文摘要，失败返回空数组） */
export async function fetchNewsSuggestions(limit = 7) {
  try {
    const res = await fetch(
      `${BASE}/desktop/suggestions/news?limit=${encodeURIComponent(limit)}&_=${Date.now()}`,
      { cache: 'no-store' }
    );
    const data = await res.json().catch(() => ({}));
    if (!res.ok || data.code !== 200) return [];
    const rows = Array.isArray(data.data) ? data.data : [];
    return rows
      .map((row) => {
        if (typeof row === 'string') {
          const t = row.trim();
          return t ? { title: t, content: t } : null;
        }
        const title = String(row?.title || '').trim();
        const content = String(row?.content || title).trim();
        if (!title && !content) return null;
        return { title: title || content, content: content || title };
      })
      .filter(Boolean);
  } catch {
    return [];
  }
}

export async function markNotificationRead(notificationId) {
  await fetch(
    `${BASE}/desktop/notifications/${encodeURIComponent(notificationId)}/read?userId=${encodeURIComponent(getUserId())}`,
    { method: 'POST' }
  );
}

/**
 * 解析 / 剥离 [VOICE] [VOICE:音色] [VOICE_SET:…] [VOICE_SWITCH:…]
 * 兼容全角括号、多余空格。
 */
export function parseVoiceTags(raw) {
  if (!raw) return { text: '', autoPlay: false, voice: null };
  // 全角［］→ 半角，便于匹配
  const normalized = String(raw).replace(/［/g, '[').replace(/］/g, ']');
  let autoPlay = false;
  let voice = null;
  const oneShot = /\[\s*VOICE\s*:\s*([^\]]+?)\s*\]/i.exec(normalized);
  if (oneShot) {
    autoPlay = true;
    voice = oneShot[1].trim() || null;
  }
  if (/\[\s*VOICE\s*\]/i.test(normalized)) autoPlay = true;
  const text = normalized
    .replace(/\[\s*VOICE_SET\s*:[^\]]*\]/gi, '')
    .replace(/\[\s*VOICE_SWITCH\s*:[^\]]*\]/gi, '')
    .replace(/\[\s*VOICE\s*:[^\]]*\]/gi, '')
    .replace(/\[\s*VOICE\s*\]/gi, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return { text, autoPlay, voice };
}

export function stripVoiceTags(raw) {
  return parseVoiceTags(raw).text;
}

/** 剥掉 [FILE:…] 绝对路径标记 */
export function stripFileMarkers(raw) {
  if (!raw) return '';
  return String(raw)
    .replace(/\[FILE:[^\]]*\]/gi, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** 展示用：剥语音 + 文件标记 */
export function stripAssistantMarkers(raw) {
  return stripFileMarkers(stripVoiceTags(raw));
}

/**
 * 发送登录邮箱验证码
 * POST /api/v1/mail/send-code
 */
export async function sendLoginEmailCode(email) {
  const res = await fetch(`${BASE}/mail/send-code`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: String(email || '').trim() }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.code !== 200) {
    throw new Error(data.message || `发送失败: ${res.status}`);
  }
  return true;
}

/**
 * 校验登录邮箱验证码；成功返回 { userId, email }
 * POST /api/v1/mail/verify-code
 */
export async function verifyLoginEmailCode(email, code) {
  const res = await fetch(`${BASE}/mail/verify-code`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: String(email || '').trim(),
      code: String(code || '').trim(),
    }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.code !== 200) {
    throw new Error(data.message || `校验失败: ${res.status}`);
  }
  const payload = data.data || {};
  if (!payload.userId) {
    throw new Error('登录失败：未返回用户 ID');
  }
  return payload;
}

/**
 * 合成语音 → Audio 可播放的 object URL（audio/mpeg）
 */
export async function synthesizeSpeech(text, voice) {
  const plain = stripVoiceTags(text);
  if (!plain) throw new Error('没有可朗读的文字');

  const res = await fetch(`${BASE}/tts/synthesize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      text: plain,
      voice: voice || undefined,
      userId: getUserId(),
    })
  });
  if (!res.ok) {
    throw new Error(res.status === 400 ? '朗读内容无效' : '语音合成失败');
  }
  const blob = await res.blob();
  if (!blob.size) throw new Error('语音合成失败');
  return URL.createObjectURL(blob);
}
