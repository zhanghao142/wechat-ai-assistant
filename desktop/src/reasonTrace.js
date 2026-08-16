/**
 * 思维链：阶段跃迁时生成叙述段落；侧栏以散文展示逻辑，而非标签卡片。
 */

let _seq = 0;
function nextId() {
  _seq += 1;
  return `rs-${Date.now()}-${_seq}`;
}

function step(kind, title, status = 'done', meta = {}) {
  return {
    id: nextId(),
    kind,
    title,
    status,
    at: Date.now(),
    ...meta,
  };
}

/** 本轮最多几段叙述（含开场+收束） */
export const MAX_NARRATED = 5;

export function isRunningProgress(text) {
  return /正在|并行处理/.test(text || '');
}

export function isFinishProgress(text) {
  return /完成|已获取|已打开|已生成|搜到|未搜到|失败|跳过|需要确认|遇到问题|已处理完毕|就绪/.test(
    text || '',
  );
}

export function classifyProgress(text) {
  const t = String(text || '');
  if (/需要确认|等待确认|请确认/.test(t)) return 'confirm';
  if (/失败|未搜到|遇到问题|未成功|超时/.test(t) && !/截图完成/.test(t)) return 'error';
  if (/生成|写入|文档|\.md|文件已|已保存|图表/.test(t)) return 'write';
  if (/搜到|搜索|未搜到|网页搜索/.test(t)) return 'search';
  if (/已获取|读取|网页内容|打开页面|截图|浏览/.test(t)) return 'read';
  if (/正在/.test(t)) return 'busy';
  return 'other';
}

function familyOf(bucket) {
  if (bucket === 'search' || bucket === 'busy') return 'search';
  if (bucket === 'read') return 'read';
  if (bucket === 'write' || bucket === 'confirm') return 'write';
  if (bucket === 'error') return 'error';
  return 'other';
}

export function createTurnContext(userText) {
  return {
    userText: String(userText || '').trim(),
    facts: [],
    replyStarted: false,
    narrateCount: 0,
    lastNarrateFamily: null,
    progressSinceNarrate: 0,
    seenFamilies: [],
    liveProbeId: null,
    searchDone: 0,
    readDone: 0,
    writeDone: 0,
    errorDone: 0,
  };
}

/** 已落成的叙述段落（供续写去重） */
export function recentTitles(steps, n = 6) {
  const list = Array.isArray(steps) ? steps : [];
  return list
    .filter((s) => !s.silent)
    .map((s) => s.title)
    .filter((t) => t && t !== '…' && !/^正在/.test(t))
    .slice(-n);
}

/** 工作量摘要：让独白能写出「做了多少」而不是空喊 */
export function workloadHint(ctx) {
  if (!ctx) return '';
  const parts = [];
  if (ctx.searchDone) parts.push(`检索完成约 ${ctx.searchDone} 轮`);
  if (ctx.readDone) parts.push(`精读约 ${ctx.readDone} 次`);
  if (ctx.writeDone) parts.push(`文档/写入相关 ${ctx.writeDone} 次`);
  if (ctx.errorDone) parts.push(`受阻/失败 ${ctx.errorDone} 次`);
  return parts.join('，');
}

export function factsHint(ctx) {
  const facts = ctx?.facts || [];
  const work = workloadHint(ctx);
  const lines = facts
    .filter((f) => f && !/^正在/.test(f))
    .slice(-6);
  const bits = [];
  if (work) bits.push(work);
  if (lines.length) bits.push(lines.join('；'));
  return bits.join('。');
}

function bumpWorkload(ctx, bucket) {
  if (!ctx) return;
  if (bucket === 'search') ctx.searchDone = (ctx.searchDone || 0) + 1;
  else if (bucket === 'read') ctx.readDone = (ctx.readDone || 0) + 1;
  else if (bucket === 'write' || bucket === 'confirm') ctx.writeDone = (ctx.writeDone || 0) + 1;
  else if (bucket === 'error') ctx.errorDone = (ctx.errorDone || 0) + 1;
}

function pushFact(ctx, progressText) {
  const t = String(progressText || '').trim();
  if (!t || !ctx) return;
  if (ctx.facts[ctx.facts.length - 1] === t) return;
  ctx.facts.push(t);
  if (ctx.facts.length > 20) ctx.facts.shift();
}

function closeRunningProbes(steps, err = false) {
  return steps.map((s) => {
    if (s.kind === 'probe' && s.status === 'running') {
      return { ...s, status: err ? 'error' : 'done' };
    }
    return s;
  });
}

function stripSilent(steps) {
  return (steps || []).filter((s) => !s.silent);
}

function shouldNarrateMilestone(ctx, bucket) {
  const count = ctx.narrateCount || 0;
  if (count >= MAX_NARRATED - 1) return false;

  if (bucket === 'confirm' || bucket === 'write' || bucket === 'error') return true;

  const family = familyOf(bucket);
  if (family !== 'search' && family !== 'read') return false;

  const seen = Array.isArray(ctx.seenFamilies) ? ctx.seenFamilies : [];
  if (!seen.includes(family)) return true;
  if (ctx.lastNarrateFamily && ctx.lastNarrateFamily !== family) return true;
  // 同族多次：每累计足够进度再允许一段（体现工作量推进，避免三句同义）
  if ((ctx.progressSinceNarrate || 0) >= 5 && family === ctx.lastNarrateFamily) {
    return count < MAX_NARRATED - 2;
  }
  return false;
}

function markNarrated(ctx, family) {
  ctx.narrateCount = (ctx.narrateCount || 0) + 1;
  ctx.lastNarrateFamily = family;
  ctx.progressSinceNarrate = 0;
  if (!Array.isArray(ctx.seenFamilies)) ctx.seenFamilies = [];
  if (family && !ctx.seenFamilies.includes(family)) ctx.seenFamilies.push(family);
}

export function seedTurnSteps(userText) {
  const ctx = createTurnContext(userText);
  ctx.narrateCount = 1;
  const s = step('narrative', '…', 'running', { phase: 'start', narrateKey: 'start' });
  return { steps: [s], ctx };
}

export function applyProgressToSteps(bundle, text) {
  const ctx = bundle?.ctx || createTurnContext('');
  let steps = Array.isArray(bundle?.steps) ? [...bundle.steps] : [];
  const t = String(text || '').trim();
  if (!t) return { steps, ctx, pending: null };

  ctx.progressSinceNarrate = (ctx.progressSinceNarrate || 0) + 1;

  const last = steps[steps.length - 1];
  if (last?.sourceProgress === t && last?.status === 'running') {
    return { steps, ctx, pending: null };
  }

  const bucket = classifyProgress(t);
  const family = familyOf(bucket);

  if (isRunningProgress(t)) {
    const liveIdx = ctx.liveProbeId
      ? steps.findIndex((s) => s.id === ctx.liveProbeId)
      : -1;
    if (liveIdx >= 0) {
      steps[liveIdx] = {
        ...steps[liveIdx],
        title: t,
        sourceProgress: t,
        status: 'running',
      };
      return { steps, ctx, pending: null };
    }
    steps = closeRunningProbes(stripSilent(steps), false);
    const live = step('live', t, 'running', {
      phase: 'live',
      sourceProgress: t,
      silent: true,
    });
    ctx.liveProbeId = live.id;
    steps.push(live);
    return { steps, ctx, pending: null };
  }

  if (isFinishProgress(t) || bucket !== 'other') {
    pushFact(ctx, t);
    bumpWorkload(ctx, bucket);
  }

  const err = bucket === 'error';
  steps = closeRunningProbes(steps, err);
  ctx.liveProbeId = null;

  if (!shouldNarrateMilestone(ctx, bucket)) {
    steps = stripSilent(steps);
    return { steps, ctx, pending: null };
  }

  markNarrated(ctx, family);
  steps = stripSilent(steps);

  const phase =
    bucket === 'confirm' ? 'confirm'
      : bucket === 'write' ? 'write'
        : bucket === 'error' ? 'error'
          : family === 'read' ? 'read_done'
            : 'search_done';

  const work = workloadHint(ctx);
  const recentFacts = (ctx.facts || []).filter((f) => !/^正在/.test(f)).slice(-4);
  const eventText = [
    `阶段：${phase}`,
    work ? `工作量：${work}` : '',
    `当前事件：${t}`,
    recentFacts.length ? `线索：${recentFacts.join('；')}` : '',
  ].filter(Boolean).join('\n');

  const s = step('narrative', '…', 'running', {
    phase,
    sourceProgress: t,
    narrateKey: `${phase}:${t.slice(0, 40)}`,
  });
  steps.push(s);
  return {
    steps,
    ctx,
    pending: { id: s.id, phase, eventText },
  };
}

export function applyReplyStarted(bundle) {
  const ctx = bundle?.ctx || createTurnContext('');
  let steps = Array.isArray(bundle?.steps) ? [...bundle.steps] : [];
  steps = stripSilent(steps).map((s) => {
    if (s.title === '…' && s.status === 'running') return s;
    if (s.status === 'running') return { ...s, status: 'done' };
    return s;
  });
  ctx.replyStarted = true;
  ctx.liveProbeId = null;
  return { steps, ctx, pending: null };
}

export function applyTurnFinished(bundle, ok = true, replyText = '', attachments = []) {
  const ctx = bundle?.ctx || createTurnContext('');
  // 收束前：把仍卡在占位「…」的段落收掉，避免叠出「正在理清这一段…」
  let steps = Array.isArray(bundle?.steps)
    ? stripSilent(bundle.steps).map((s) => {
        if (s.status === 'running' && (!s.title || s.title === '…')) {
          return {
            ...s,
            title: '上一段叙述未写完，先进入收束。',
            status: 'done',
          };
        }
        if (s.status === 'running') return { ...s, status: ok ? 'done' : 'error' };
        return s;
      })
    : [];

  ctx.liveProbeId = null;

  const attNames = (attachments || [])
    .map((a) => a.fileName || a.name)
    .filter(Boolean)
    .slice(0, 2);
  const replyClip = String(replyText || '').replace(/\s+/g, ' ').trim().slice(0, 100);
  const eventBits = [];
  if (!ok) eventBits.push('本轮中断或失败');
  else eventBits.push('本轮收束');
  const work = workloadHint(ctx);
  if (work) eventBits.push(`全程工作量：${work}`);
  if (replyClip) eventBits.push(`回复要点：${replyClip}`);
  if (attNames.length) eventBits.push(`附件：${attNames.join('、')}`);

  markNarrated(ctx, 'finish');

  const s = step('narrative', '…', 'running', {
    phase: 'finish',
    narrateKey: `finish:${ok}`,
  });
  steps.push(s);
  return {
    steps,
    ctx,
    pending: {
      id: s.id,
      phase: 'finish',
      eventText: eventBits.join('；'),
    },
  };
}

export function fillStepText(bundle, stepId, text, finalStatus = 'done') {
  const ctx = bundle?.ctx || createTurnContext('');
  const steps = Array.isArray(bundle?.steps) ? bundle.steps.map((s) => {
    if (s.id !== stepId) return s;
    return {
      ...s,
      title: (text && String(text).trim()) || s.sourceProgress || s.title || '…',
      status: finalStatus,
    };
  }) : [];
  return { steps, ctx };
}

/** @deprecated 保留兼容；UI 已改为散文，不再展示标签 */
export const KIND_META = {
  narrative: { label: '叙述', short: '述' },
  monologue: { label: '独白', short: '想' },
  probe: { label: '查证', short: '查' },
  verdict: { label: '判断', short: '断' },
  live: { label: '进行中', short: '…' },
};
