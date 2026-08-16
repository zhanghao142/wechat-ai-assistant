import { useState, useEffect, useRef } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import * as api from './api';
import { isTauri, syncSystemConfirmWindow } from './confirmWindow';
import { syncSystemRemindWindow } from './remindWindow';
import { notifySystem } from './systemNotify';
import newChatIcon from './assets/new-chat-icon.png';
import ReasonRail from './ReasonRail';
import RouteMapView from './RouteMapView';
import { setLastRoute, pickRouteFromAttachments } from './routeStore';
import {
  seedTurnSteps,
  applyProgressToSteps,
  applyReplyStarted,
  applyTurnFinished,
  fillStepText,
  recentTitles,
  factsHint,
} from './reasonTrace';

const DOC_EXTS = ['.pdf', '.doc', '.docx', '.txt'];
const isMarkdownFile = (name) => /\.(md|markdown)$/i.test(name || '');
const USE_SYSTEM_CONFIRM = isTauri();
const USE_SYSTEM_REMIND = isTauri();
const WELCOME_PROMPT = '请问有什么能帮您？';
/** 未登录免费轮次：用户已发满 N 条后再发需登录 */
const FREE_USER_TURNS = 3;
/** 欢迎页任务提示词池（每次随机抽 3 个） */
const TASK_SUGGESTIONS = [
  '帮我写周报',
  '总结这段文字',
  '帮我打一辆车',
  '帮我点麦当劳',
  '帮我查天气',
];

function pickRandomItems(list, n) {
  const copy = [...list];
  for (let i = copy.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy.slice(0, Math.min(n, copy.length));
}

function shortUsageTime(at) {
  const s = String(at || '');
  const m = s.match(/(\d{2})-(\d{2})\s+(\d{2}:\d{2})/);
  if (m) return `${m[1]}-${m[2]} ${m[3]}`;
  return s.slice(5, 16) || s;
}

/** 使用统计：Token / 耗时双折线（纯 SVG，无额外依赖） */
function UsageTrendChart({ turns, formatNum, formatMs }) {
  const [hover, setHover] = useState(null);
  const points = [...(turns || [])]
    .map((t) => ({
      at: t.at,
      label: shortUsageTime(t.at),
      tokens: Number(t.totalTokens) || 0,
      latency: Number(t.latencyMs) || 0,
      llm: Number(t.llmCallCount) || 0,
      tools: Number(t.toolCallCount) || 0,
    }))
    .reverse();

  if (points.length === 0) {
    return <div className="usage-empty">暂无数据，先聊几轮再回来看</div>;
  }

  const W = 640;
  const H = 260;
  const pad = { top: 24, right: 52, bottom: 40, left: 52 };
  const innerW = W - pad.left - pad.right;
  const innerH = H - pad.top - pad.bottom;
  const maxTok = Math.max(1, ...points.map((p) => p.tokens));
  const maxLat = Math.max(1, ...points.map((p) => p.latency));
  const n = points.length;
  const xAt = (i) => pad.left + (n === 1 ? innerW / 2 : (i / (n - 1)) * innerW);
  const yTok = (v) => pad.top + innerH - (v / maxTok) * innerH;
  const yLat = (v) => pad.top + innerH - (v / maxLat) * innerH;

  const tokPath = points
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${xAt(i).toFixed(1)},${yTok(p.tokens).toFixed(1)}`)
    .join(' ');
  const latPath = points
    .map((p, i) => `${i === 0 ? 'M' : 'L'}${xAt(i).toFixed(1)},${yLat(p.latency).toFixed(1)}`)
    .join(' ');

  const yTicks = 4;
  const tokTicks = Array.from({ length: yTicks + 1 }, (_, i) => Math.round((maxTok * i) / yTicks));
  const latTicks = Array.from({ length: yTicks + 1 }, (_, i) => Math.round((maxLat * i) / yTicks));
  const xLabelStep = Math.max(1, Math.ceil(n / 6));

  return (
    <div className="usage-chart">
      <div className="usage-chart-legend">
        <span className="usage-legend-item usage-legend-tok">Token / 轮</span>
        <span className="usage-legend-item usage-legend-lat">耗时</span>
      </div>
      <div className="usage-chart-frame">
        <svg
          className="usage-chart-svg"
          viewBox={`0 0 ${W} ${H}`}
          role="img"
          aria-label="近期 Token 与耗时趋势"
          onMouseLeave={() => setHover(null)}
        >
          {tokTicks.map((v, i) => {
            const y = yTok(v);
            return (
              <g key={`grid-${i}`}>
                <line
                  x1={pad.left}
                  y1={y}
                  x2={pad.left + innerW}
                  y2={y}
                  className="usage-chart-grid"
                />
                <text x={pad.left - 8} y={y + 4} textAnchor="end" className="usage-chart-axis usage-chart-axis-tok">
                  {formatNum(v)}
                </text>
                <text x={pad.left + innerW + 8} y={y + 4} textAnchor="start" className="usage-chart-axis usage-chart-axis-lat">
                  {formatMs(latTicks[i])}
                </text>
              </g>
            );
          })}
          <line
            x1={pad.left}
            y1={pad.top + innerH}
            x2={pad.left + innerW}
            y2={pad.top + innerH}
            className="usage-chart-baseline"
          />
          <path d={tokPath} className="usage-chart-line usage-chart-line-tok" />
          <path d={latPath} className="usage-chart-line usage-chart-line-lat" />
          {points.map((p, i) => (
            <g key={`${p.at}-${i}`}>
              <circle
                cx={xAt(i)}
                cy={yTok(p.tokens)}
                r={hover === i ? 5 : 3.5}
                className="usage-chart-dot usage-chart-dot-tok"
              />
              <circle
                cx={xAt(i)}
                cy={yLat(p.latency)}
                r={hover === i ? 5 : 3.5}
                className="usage-chart-dot usage-chart-dot-lat"
              />
              <rect
                x={xAt(i) - 10}
                y={pad.top}
                width={20}
                height={innerH}
                fill="transparent"
                onMouseEnter={() => setHover(i)}
              />
              {(i % xLabelStep === 0 || i === n - 1) && (
                <text
                  x={xAt(i)}
                  y={H - 12}
                  textAnchor="middle"
                  className="usage-chart-axis usage-chart-x"
                >
                  {p.label}
                </text>
              )}
            </g>
          ))}
        </svg>
        {hover != null && points[hover] && (
          <div
            className="usage-chart-tooltip"
            style={{ left: `${(xAt(hover) / W) * 100}%` }}
          >
            <div className="usage-chart-tip-time">{points[hover].at}</div>
            <div>Token {formatNum(points[hover].tokens)}</div>
            <div>耗时 {formatMs(points[hover].latency)}</div>
            <div>LLM {points[hover].llm} · 工具 {points[hover].tools}</div>
          </div>
        )}
      </div>
    </div>
  );
}

function App() {
  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  /** 工具执行进度文案（淡灰），正式回复开始后清空 */
  const [progressText, setProgressText] = useState('');
  const [sessions, setSessions] = useState(api.getSessionList());
  const [currentId, setCurrentId] = useState(api.newSession);
  /** 空会话欢迎语文流式展示 */
  const [welcomeShown, setWelcomeShown] = useState('');
  /** 欢迎页：3 个固定任务提示 + 新闻，打乱后混排 */
  const [welcomeChips, setWelcomeChips] = useState([]);
  const [newsLoading, setNewsLoading] = useState(true);
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [sidebarToggleReveal, setSidebarToggleReveal] = useState(false);
  /** 收起时悬停打开按钮：临时预览缩小侧栏（独立浮层，不打断主侧栏宽度动画） */
  const [sidebarPeek, setSidebarPeek] = useState(false);
  /** 登录浮层（默认仍进聊天页，点登录才打开） */
  const [showLogin, setShowLogin] = useState(false);
  const [loginClosing, setLoginClosing] = useState(false);
  /** 1=邮箱 2=验证码（后续步骤） */
  const [loginStep, setLoginStep] = useState(1);
  const [loginEmail, setLoginEmail] = useState('');
  const [otpDigits, setOtpDigits] = useState(['', '', '', '', '', '']);
  /** 验证码重发倒计时（秒），0 可点重新发送 */
  const [otpCountdown, setOtpCountdown] = useState(0);
  const [otpBusy, setOtpBusy] = useState(false);
  const [loggedIn, setLoggedIn] = useState(() => api.isLoggedIn());
  /** 登录后：自动语音播报开关（默认关） */
  const [autoVoice, setAutoVoice] = useState(() => localStorage.getItem('autoVoice') === '1');
  const autoVoiceRef = useRef(autoVoice);
  /** 删除会话确认浮层 */
  const [deleteConfirmId, setDeleteConfirmId] = useState(null);
  const [deleteClosing, setDeleteClosing] = useState(false);
  /** 左下角用户名菜单（退出登录） */
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  /** 主区：chat | usage */
  const [mainView, setMainView] = useState('chat');
  const [usageData, setUsageData] = useState(null);
  const [usageLoading, setUsageLoading] = useState(false);
  const [usageError, setUsageError] = useState('');
  /** 右侧思维链：功能始终开启；仅控制面板展开 */
  const [reasonRailOpen, setReasonRailOpen] = useState(
    () => localStorage.getItem('reasonRailOpen') === '1',
  );
  /** sessionId → { steps, ctx } 伪思维链回合包（收起时也写） */
  const reasonBySessionRef = useRef({});
  const [reasonSteps, setReasonSteps] = useState([]);
  const [reasonCtx, setReasonCtx] = useState(null);
  const [reasonActive, setReasonActive] = useState(false);
  const [loginErrorVisible, setLoginErrorVisible] = useState(false);
  const [loginErrorClosing, setLoginErrorClosing] = useState(false);
  const [loginErrorMessage, setLoginErrorMessage] = useState('邮箱错误，请重新填写');
  const loginEmailRef = useRef(null);
  const otpRefs = useRef([]);
  const loginEmailErrorTimerRef = useRef(null);
  const otpCountdownTimerRef = useRef(null);
  const otpSubmittingRef = useRef(false);
  /** 因轮次限制弹登录：成功后自动发送当前输入框内容 */
  const pendingSendAfterLoginRef = useRef(false);

  useEffect(() => {
    autoVoiceRef.current = autoVoice;
  }, [autoVoice]);

  function toggleAutoVoice() {
    setAutoVoice(v => {
      const next = !v;
      localStorage.setItem('autoVoice', next ? '1' : '0');
      return next;
    });
  }

  function clearLoginEmailErrorTimer() {
    if (loginEmailErrorTimerRef.current) {
      clearTimeout(loginEmailErrorTimerRef.current);
      loginEmailErrorTimerRef.current = null;
    }
  }

  function hideLoginEmailError() {
    if (!loginErrorVisible || loginErrorClosing) return;
    clearLoginEmailErrorTimer();
    setLoginErrorClosing(true);
  }

  function showLoginEmailError(message) {
    clearLoginEmailErrorTimer();
    setLoginErrorMessage(message || '邮箱错误，请重新填写');
    setLoginErrorClosing(false);
    setLoginErrorVisible(true);
    loginEmailErrorTimerRef.current = setTimeout(() => {
      setLoginErrorClosing(true);
      loginEmailErrorTimerRef.current = null;
    }, 2500);
  }

  function handleLoginErrorAnimEnd(e) {
    if (e.target !== e.currentTarget) return;
    if (loginErrorClosing) {
      setLoginErrorVisible(false);
      setLoginErrorClosing(false);
    }
  }

  function clearOtpCountdown() {
    if (otpCountdownTimerRef.current) {
      clearInterval(otpCountdownTimerRef.current);
      otpCountdownTimerRef.current = null;
    }
    setOtpCountdown(0);
  }

  function startOtpCountdown(seconds = 60) {
    if (otpCountdownTimerRef.current) {
      clearInterval(otpCountdownTimerRef.current);
      otpCountdownTimerRef.current = null;
    }
    setOtpCountdown(seconds);
    otpCountdownTimerRef.current = setInterval(() => {
      setOtpCountdown(prev => {
        if (prev <= 1) {
          clearInterval(otpCountdownTimerRef.current);
          otpCountdownTimerRef.current = null;
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
  }

  /** 发送验证码：调用后端 /api/v1/mail/send-code */
  async function sendLoginCode() {
    try {
      await api.sendLoginEmailCode(loginEmail);
      startOtpCountdown(60);
    } catch (e) {
      clearOtpCountdown();
      console.warn('[login] send code failed', e);
      showLoginEmailError(e?.message || '验证码发送失败');
    }
  }

  function resetLoginForm() {
    setLoginStep(1);
    setLoginEmail('');
    setOtpDigits(['', '', '', '', '', '']);
    setLoginErrorVisible(false);
    setLoginErrorClosing(false);
    clearLoginEmailErrorTimer();
    clearOtpCountdown();
  }

  function openLogin() {
    setLoginClosing(false);
    resetLoginForm();
    setShowLogin(true);
  }

  function toggleUserMenu(e) {
    e?.stopPropagation?.();
    setUserMenuOpen(v => !v);
  }

  async function loadUsageStats() {
    setUsageLoading(true);
    setUsageError('');
    try {
      const data = await api.fetchUsageStats(30);
      setUsageData(data);
    } catch (e) {
      setUsageError(e?.message || '加载失败');
      setUsageData(null);
    } finally {
      setUsageLoading(false);
    }
  }

  function openUsagePage() {
    setUserMenuOpen(false);
    setMainView('usage');
    loadUsageStats();
  }

  function openRouteMap() {
    setUserMenuOpen(false);
    setSidebarPeek(false);
    setMainView('routeMap');
  }

  function backToChat() {
    setMainView('chat');
  }

  function handleLogout() {
    streamAbortRef.current?.abort();
    reasonAbortRef.current?.abort();
    streamGenRef.current += 1;
    streamActiveRef.current = false;
    streamBubbleIdRef.current = null;
    setLoading(false);
    api.clearUserId();
    const id = api.resetGuestWorkspace();
    setLoggedIn(false);
    setUserMenuOpen(false);
    setMainView('chat');
    setUsageData(null);
    setSessions(api.getSessionList());
    setCurrentId(id);
    setMessages([]);
    setProgressText('');
    clearPendingImage();
    clearPendingDoc();
    setInput('');
    syncConfirmUi(id);
  }

  function formatMs(ms) {
    const n = Number(ms) || 0;
    if (n < 1000) return `${n} ms`;
    return `${(n / 1000).toFixed(1)} s`;
  }

  function formatNum(n) {
    return Number(n || 0).toLocaleString('zh-CN');
  }

  function renderUserMenu() {
    if (!userMenuOpen) return null;
    return (
      <div className="sidebar-user-menu" role="menu">
        <button type="button" role="menuitem" onClick={openRouteMap}>
          路线地图
        </button>
        <button type="button" role="menuitem" onClick={openUsagePage}>
          使用统计
        </button>
        <button type="button" role="menuitem" onClick={handleLogout}>
          退出登录
        </button>
      </div>
    );
  }

  /**
   * @param {{ keepCurrent?: boolean }} opts
   * keepCurrent：登录后继续访客当前会话，等发消息时后端绑定 userId（不跳到旧 list[0]）
   */
  async function applyLoggedInSessions(opts = {}) {
    const keepCurrent = !!opts.keepCurrent;
    try {
      const preferId = keepCurrent ? api.getCurrentSessionId() : null;
      let list = await api.refreshSessionsFromServer();
      if (preferId && !list.some(s => s.id === preferId)) {
        api.ensureSession(preferId, '新对话', { switchTo: false });
        list = api.getSessionList();
      }
      setSessions(list);
      let id = preferId || api.getCurrentSessionId();
      if (preferId) {
        api.switchSession(preferId);
        setCurrentId(preferId);
        return preferId;
      }
      if (!id || !list.some(s => s.id === id)) {
        id = list[0]?.id || api.newSession();
        if (list[0]?.id) api.switchSession(id);
        else setSessions(api.getSessionList());
      }
      setCurrentId(id);
      return id;
    } catch (e) {
      console.warn('[sessions] refresh failed', e);
      setSessions(api.getSessionList());
      return api.getCurrentSessionId();
    }
  }

  useEffect(() => {
    if (!api.isLoggedIn()) return undefined;
    let cancelled = false;
    (async () => {
      if (cancelled) return;
      await applyLoggedInSessions();
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 仅启动时拉取一次
  }, []);

  /** 侧栏宽度随窗口变化（直接写 CSS 变量，避免仅靠 vw 不明显/不刷新） */
  useEffect(() => {
    const applySidebarWidth = () => {
      const w = window.innerWidth || document.documentElement.clientWidth || 1280;
      const side = Math.round(Math.min(440, Math.max(180, w * 0.18)));
      const peek = Math.round(Math.min(300, Math.max(160, w * 0.14)));
      const root = document.documentElement;
      root.style.setProperty('--sidebar-width', `${side}px`);
      root.style.setProperty('--sidebar-peek-width', `${peek}px`);
    };
    applySidebarWidth();
    window.addEventListener('resize', applySidebarWidth);
    return () => window.removeEventListener('resize', applySidebarWidth);
  }, []);

  useEffect(() => {
    if (!userMenuOpen) return undefined;
    const onDoc = (e) => {
      if (e.target?.closest?.('.sidebar-user-wrap')) return;
      setUserMenuOpen(false);
    };
    const onKey = (e) => {
      if (e.key === 'Escape') setUserMenuOpen(false);
    };
    window.addEventListener('mousedown', onDoc);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('mousedown', onDoc);
      window.removeEventListener('keydown', onKey);
    };
  }, [userMenuOpen]);

  useEffect(() => {
    if (!showLogin || loginClosing) return undefined;
    const t = setTimeout(() => {
      if (loginStep === 1) loginEmailRef.current?.focus();
      else otpRefs.current[0]?.focus();
    }, 50);
    return () => clearTimeout(t);
  }, [showLogin, loginClosing, loginStep]);

  function closeLogin() {
    if (!showLogin || loginClosing) return;
    setLoginClosing(true);
  }

  function handleLoginAnimEnd(e) {
    if (e.target !== e.currentTarget) return;
    if (loginClosing) {
      setShowLogin(false);
      setLoginClosing(false);
      resetLoginForm();
    }
  }

  function isValidLoginEmail(email) {
    // 基础邮箱格式校验（含 qq 邮箱等）
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(email || '').trim());
  }

  const loginEmailHasInput = !!loginEmail.trim();

  function handleLoginNext() {
    if (!loginEmailHasInput) return;
    if (!isValidLoginEmail(loginEmail)) {
      showLoginEmailError();
      loginEmailRef.current?.focus();
      return;
    }
    hideLoginEmailError();
    setOtpDigits(['', '', '', '', '', '']);
    setLoginStep(2);
    sendLoginCode(); // 进入验证码页自动发送（接口暂未接）
  }

  useEffect(() => () => {
    clearLoginEmailErrorTimer();
    if (otpCountdownTimerRef.current) {
      clearInterval(otpCountdownTimerRef.current);
      otpCountdownTimerRef.current = null;
    }
  }, []);

  function handleLoginBack() {
    setOtpDigits(['', '', '', '', '', '']);
    clearOtpCountdown();
    setLoginStep(1);
  }

  function handleResendLoginCode() {
    if (otpCountdown > 0) return;
    sendLoginCode();
    setOtpDigits(['', '', '', '', '', '']);
    requestAnimationFrame(() => focusOtpBox(0));
  }

  function focusOtpBox(index) {
    const i = Math.max(0, Math.min(5, index));
    otpRefs.current[i]?.focus();
  }

  function handleOtpChange(index, raw) {
    const digits = String(raw || '').replace(/\D/g, '');
    if (!digits) {
      setOtpDigits(prev => {
        const next = [...prev];
        next[index] = '';
        return next;
      });
      return;
    }
    setOtpDigits(prev => {
      const next = [...prev];
      let i = index;
      for (const d of digits) {
        if (i > 5) break;
        next[i] = d;
        i += 1;
      }
      requestAnimationFrame(() => focusOtpBox(i >= 6 ? 5 : i));
      return next;
    });
  }

  function handleOtpKeyDown(index, e) {
    if (e.key === 'Backspace') {
      if (otpDigits[index]) {
        setOtpDigits(prev => {
          const next = [...prev];
          next[index] = '';
          return next;
        });
        return;
      }
      if (index > 0) {
        e.preventDefault();
        setOtpDigits(prev => {
          const next = [...prev];
          next[index - 1] = '';
          return next;
        });
        focusOtpBox(index - 1);
      }
      return;
    }
    if (e.key === 'ArrowLeft' && index > 0) {
      e.preventDefault();
      focusOtpBox(index - 1);
    } else if (e.key === 'ArrowRight' && index < 5) {
      e.preventDefault();
      focusOtpBox(index + 1);
    }
  }

  function handleOtpPaste(e) {
    e.preventDefault();
    const text = (e.clipboardData?.getData('text') || '').replace(/\D/g, '').slice(0, 6);
    if (!text) return;
    const next = ['', '', '', '', '', ''];
    for (let i = 0; i < text.length; i += 1) next[i] = text[i];
    setOtpDigits(next);
    requestAnimationFrame(() => focusOtpBox(Math.min(text.length, 5)));
  }

  async function submitLoginCode(code) {
    if (otpSubmittingRef.current || otpBusy) return;
    otpSubmittingRef.current = true;
    setOtpBusy(true);
    hideLoginEmailError();
    try {
      const data = await api.verifyLoginEmailCode(loginEmail, code);
      // 带入访客当前会话；下次发消息时后端 rebind 到真实 userId
      api.loginAs(data.userId);
      setLoggedIn(true);
      await applyLoggedInSessions({ keepCurrent: true });
      closeLogin();
      if (pendingSendAfterLoginRef.current) {
        pendingSendAfterLoginRef.current = false;
        // 等登录浮层开始关闭后再发，输入框内容仍在
        queueMicrotask(() => { handleSend(); });
      }
    } catch (e) {
      console.warn('[login] verify failed', e);
      showLoginEmailError(e?.message || '验证码错误');
      setOtpDigits(['', '', '', '', '', '']);
      requestAnimationFrame(() => focusOtpBox(0));
    } finally {
      otpSubmittingRef.current = false;
      setOtpBusy(false);
    }
  }

  useEffect(() => {
    if (loginStep !== 2 || otpBusy) return;
    const code = otpDigits.join('');
    if (code.length !== 6) return;
    submitLoginCode(code);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 仅在六位码填满时触发
  }, [otpDigits, loginStep]);

  function collapseSidebar() {
    setSidebarPeek(false);
    setSidebarToggleReveal(false);
    setSidebarOpen(false);
  }

  function expandSidebar() {
    setSidebarPeek(false);
    setSidebarOpen(true);
  }
  /** 会话右键菜单 { x, y, id, pinned } */
  const [ctxMenu, setCtxMenu] = useState(null);
  const [renamingId, setRenamingId] = useState(null);
  const [renameValue, setRenameValue] = useState('');
  const renameInputRef = useRef(null);
  const [pendingImage, setPendingImage] = useState(null); // { file, previewUrl }
  const [pendingDoc, setPendingDoc] = useState(null); // { file, name }
  /** 拖拽文件进入主聊天区时的高亮 */
  const [fileDragOver, setFileDragOver] = useState(false);
  const fileDragDepthRef = useRef(0);
  const [awaitingConfirm, setAwaitingConfirm] = useState(false);
  /** 右下角确认卡片（人不在对应会话/窗口失焦时） */
  const [confirmCards, setConfirmCards] = useState([]);
  const [confirmBusyId, setConfirmBusyId] = useState(null);
  const [speakingKey, setSpeakingKey] = useState(null); // 正在合成/播放的消息 key
  /** 附件预览：{ fileName, text } | null */
  const [filePreview, setFilePreview] = useState(null);
  const [filePreviewBusy, setFilePreviewBusy] = useState(false);
  const bottomRef = useRef(null);
  const textareaRef = useRef(null);
  const attachInputRef = useRef(null);
  const streamAbortRef = useRef(null);
  /** 思维链叙述专用：与聊天 SSE 分离，避免收束段被误 abort 卡在「正在理清」 */
  const reasonAbortRef = useRef(null);
  const streamGenRef = useRef(0);
  /** 正在流式输出时禁止历史回写，否则会去掉 streaming 标记导致再插一条助手消息 */
  const streamActiveRef = useRef(false);
  /** 使过期的 getHistory 结果失效 */
  const historyGenRef = useRef(0);
  /** 当前流式气泡 id，按 id 追加，避免竞态新建第二条 */
  const streamBubbleIdRef = useRef(null);
  const audioRef = useRef(null);
  const audioUrlRef = useRef(null);
  /** sessionId → { description, confirmThreadId } */
  const pendingConfirmsRef = useRef({});
  const currentIdRef = useRef(currentId);
  useEffect(() => {
    if (mainView !== 'chat') return;
    // 下一帧再滚：从使用统计切回时 bottomRef 刚挂载
    const id = requestAnimationFrame(() => {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    });
    return () => cancelAnimationFrame(id);
  }, [messages, loading, mainView]);

  useEffect(() => {
    currentIdRef.current = currentId;
  }, [currentId]);

  /**
   * 任意 WRITE 确认（setRemind / write_file / sendEmail / …）。
   * Tauri：屏幕右下角独立置顶窗；浏览器：应用内浮层。仍可打字确认。
   */
  function syncConfirmUi(viewSessionId) {
    const pending = pendingConfirmsRef.current;
    const cards = Object.entries(pending).map(([sid, info]) => ({
      sessionId: sid,
      description: info.description || '需要确认一项操作',
      confirmThreadId: info.confirmThreadId || null
    }));
    setConfirmCards(USE_SYSTEM_CONFIRM ? [] : cards);
    setAwaitingConfirm(!!(viewSessionId && pending[viewSessionId]));
    if (USE_SYSTEM_CONFIRM) {
      syncSystemConfirmWindow(cards).catch(err => {
        console.warn('[confirm] 系统确认窗失败，回退应用内浮层', err);
        setConfirmCards(cards);
      });
    }
  }

  // 系统确认窗点确认/取消后，同步主窗口状态（不切会话）
  useEffect(() => {
    if (!USE_SYSTEM_CONFIRM) return undefined;
    let unlisten = () => {};
    (async () => {
      try {
        const { listen } = await import('@tauri-apps/api/event');
        unlisten = await listen('confirm-popup-resolved', (ev) => {
          const p = ev.payload || {};
          const sessionId = p.sessionId;
          if (!sessionId) return;
          if (p.finishReason === 'NEED_CONFIRM') {
            pendingConfirmsRef.current[sessionId] = {
              description: (p.reply || '').trim() || '需要确认一项操作',
              confirmThreadId: p.confirmThreadId || null
            };
          } else {
            delete pendingConfirmsRef.current[sessionId];
          }
          if (sessionId === currentIdRef.current) {
            const cached = api.getCachedSessionMessages(sessionId);
            setMessages(cached);
          }
          syncConfirmUi(currentIdRef.current);
        });
      } catch (e) {
        console.warn('[confirm] listen failed', e);
      }
    })();
    return () => { try { unlisten(); } catch { /* ignore */ } };
  }, []);

  function upsertPendingConfirm(sessionId, description, confirmThreadId) {
    if (!sessionId) return;
    pendingConfirmsRef.current[sessionId] = {
      description: (description || '').trim() || '需要确认一项操作',
      confirmThreadId: confirmThreadId || null
    };
    syncConfirmUi(currentIdRef.current);
  }

  function clearPendingConfirm(sessionId) {
    if (!sessionId || !pendingConfirmsRef.current[sessionId]) {
      syncConfirmUi(currentIdRef.current);
      return;
    }
    delete pendingConfirmsRef.current[sessionId];
    syncConfirmUi(currentIdRef.current);
  }

  /** 系统提醒窗当前排队（避免关掉后被再次 sync 出来） */
  const remindQueueRef = useRef([]);

  // 启动时清掉上次残留的确认/提醒弹窗（避免重启误弹）
  useEffect(() => {
    try {
      localStorage.removeItem('confirmPopupPayload');
      localStorage.removeItem('remindPopupPayload');
    } catch { /* ignore */ }
    remindQueueRef.current = [];
    if (USE_SYSTEM_CONFIRM) {
      syncSystemConfirmWindow([]).catch(() => {});
    }
    if (USE_SYSTEM_REMIND) {
      syncSystemRemindWindow([]).catch(() => {});
    }
  }, []);

  // 切会话：优先立刻展示本地缓存，再向后端同步（失败则保持缓存）
  useEffect(() => {
    const myGen = ++historyGenRef.current;
    let cancelled = false;
    const cached = api.getCachedSessionMessages(currentId);
    if (!streamActiveRef.current) {
      setMessages(cached.length ? cached : []);
    }
    syncConfirmUi(currentId);
    (async () => {
      const h = await api.getHistory(currentId);
      if (cancelled || myGen !== historyGenRef.current) return;
      if (streamActiveRef.current) return;
      setMessages(h);
      // 不从历史恢复确认弹窗：后端 pending 是内存态，重启后已失效；
      // 仅当次对话 SSE/响应返回 NEED_CONFIRM 时再弹。
    })();
    return () => { cancelled = true; };
  }, [currentId]);

  // 消息稳定后写入本地缓存（后端挂掉切回来仍能渲染）
  useEffect(() => {
    if (!currentId || loading || streamActiveRef.current) return;
    if (messages.some(m => m.streaming)) return;
    api.cacheSessionMessages(currentId, messages);
  }, [messages, currentId, loading]);

  // 桌面提醒轮询：会话内落一条消息；Tauri 再弹屏幕右下角系统小窗（不强制切会话）
  useEffect(() => {
    let cancelled = false;

    async function pushRemindUi(n) {
      if (!n?.sessionId) return;
      const onCurrent = n.sessionId === api.getCurrentSessionId();
      if (onCurrent) {
        setMessages(prev => {
          if (prev.some(m => m.role === 'assistant' && m.text === n.content)) return prev;
          return [...prev, { role: 'assistant', text: n.content, finishReason: 'REMIND' }];
        });
      } else {
        api.ensureSession(n.sessionId, n.content, { switchTo: false });
        setSessions(api.getSessionList());
        try {
          const h = await api.getHistory(n.sessionId);
          if (!cancelled) api.cacheSessionMessages(n.sessionId, h);
        } catch { /* ignore */ }
      }

      if (USE_SYSTEM_REMIND) {
        remindQueueRef.current = [
          ...remindQueueRef.current.filter(x => x.notificationId !== n.notificationId),
          {
            notificationId: n.notificationId,
            sessionId: n.sessionId,
            content: n.content || '提醒'
          }
        ];
        syncSystemRemindWindow(remindQueueRef.current).catch(err => {
          console.warn('[remind] 系统提醒窗失败', err);
        });
        // 系统 Toast（主窗口最小化时也能看到）
        notifySystem({
          title: '小微提醒',
          body: n.content || '你有一条新提醒',
        }).catch(() => {});
      }
    }

    async function tick() {
      try {
        const list = await api.pollNotifications();
        if (cancelled || !list.length) return;
        for (const n of list) {
          await api.markNotificationRead(n.notificationId);
          await pushRemindUi(n);
        }
      } catch {
        // ignore poll errors
      }
    }
    tick();
    const timer = setInterval(tick, 3000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  // 系统提醒窗：知道了 / 打开对话
  useEffect(() => {
    if (!USE_SYSTEM_REMIND) return undefined;
    let unlisten = () => {};
    (async () => {
      try {
        const { listen } = await import('@tauri-apps/api/event');
        unlisten = await listen('remind-popup-dismiss', async (ev) => {
          const p = ev.payload || {};
          remindQueueRef.current = remindQueueRef.current.filter(c => {
            if (p.notificationId) return c.notificationId !== p.notificationId;
            return c.sessionId !== p.sessionId;
          });
          syncSystemRemindWindow(remindQueueRef.current).catch(() => {});

          if (p.open && p.sessionId) {
            api.ensureSession(p.sessionId, '提醒', { switchTo: true });
            setCurrentId(p.sessionId);
            setSessions(api.getSessionList());
            try {
              const { getCurrentWindow } = await import('@tauri-apps/api/window');
              await getCurrentWindow().unminimize();
              await getCurrentWindow().setFocus();
            } catch { /* ignore */ }
          }
        });
      } catch (e) {
        console.warn('[remind] listen failed', e);
      }
    })();
    return () => { try { unlisten(); } catch { /* ignore */ } };
  }, []);

  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
  }, [input]);

  // 新会话 / 切到空会话：欢迎语从左到右流式出现
  useEffect(() => {
    if (messages.length > 0 || loading) {
      setWelcomeShown('');
      return undefined;
    }
    let i = 0;
    let timer = null;
    let cancelled = false;
    setWelcomeShown('');
    const tick = () => {
      if (cancelled) return;
      i += 1;
      setWelcomeShown(WELCOME_PROMPT.slice(0, i));
      if (i < WELCOME_PROMPT.length) {
        timer = setTimeout(tick, 40);
      }
    };
    timer = setTimeout(tick, 40);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [currentId, messages.length, loading]);

  // 空会话：抽 3 个固定提示词 + 7 条新闻 = 10 格，排布固定 上3/中4/下3
  useEffect(() => {
    if (messages.length > 0 || loading) return undefined;
    setWelcomeChips([]);
    setNewsLoading(true);
    let cancelled = false;
    (async () => {
      // 多拉几条，确保能凑满 7 条新闻（否则底行会缺格）
      const items = (await api.fetchNewsSuggestions(10)).slice(0, 7);
      if (cancelled) return;
      if (items.length < 7) {
        setWelcomeChips([]);
        setNewsLoading(false);
        return;
      }
      const tasks = pickRandomItems(TASK_SUGGESTIONS, 3).map(t => ({
        key: `task:${t}`,
        label: t,
        send: t,
        kind: 'task',
      }));
      const news = items.map((item, i) => {
        const text = String(item.content || item.title || '').trim();
        return {
          key: `news:${i}:${item.title || text}`,
          label: text,
          send: `帮我了解这条新闻：${item.title || text}`,
          kind: 'news',
        };
      });
      // 正好 3 个任务 + 7 条新闻，打乱后按 3/4/3 切开
      setWelcomeChips(pickRandomItems([...tasks, ...news], 10));
      setNewsLoading(false);
    })();
    return () => { cancelled = true; };
  }, [currentId, messages.length, loading]);

  function clearPendingImage() {
    if (pendingImage?.previewUrl) URL.revokeObjectURL(pendingImage.previewUrl);
    setPendingImage(null);
    if (attachInputRef.current) attachInputRef.current.value = '';
  }

  function clearPendingDoc() {
    setPendingDoc(null);
    if (attachInputRef.current) attachInputRef.current.value = '';
  }

  function stopSpeech() {
    if (audioRef.current) {
      try {
        audioRef.current.pause();
        audioRef.current.removeAttribute('src');
        audioRef.current.load();
      } catch { /* ignore */ }
    }
    if (audioUrlRef.current) {
      URL.revokeObjectURL(audioUrlRef.current);
      audioUrlRef.current = null;
    }
    setSpeakingKey(null);
  }

  /** 在用户点击发送的手势里解锁 Audio，避免流式结束后自动播被浏览器拦截 */
  function unlockAudioForAutoplay() {
    try {
      if (!audioRef.current) audioRef.current = new Audio();
      const a = audioRef.current;
      a.src = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=';
      const p = a.play();
      if (p && typeof p.then === 'function') {
        p.then(() => { a.pause(); a.currentTime = 0; }).catch(() => {});
      }
    } catch { /* ignore */ }
  }

  async function playSpeech(key, text, voice, { quiet } = {}) {
    const plain = api.stripVoiceTags(text);
    if (!plain) return;
    if (audioUrlRef.current) {
      URL.revokeObjectURL(audioUrlRef.current);
      audioUrlRef.current = null;
    }
    if (audioRef.current) {
      try { audioRef.current.pause(); } catch { /* ignore */ }
    }
    setSpeakingKey(key);
    try {
      const url = await api.synthesizeSpeech(plain, voice);
      audioUrlRef.current = url;
      const audio = audioRef.current || new Audio();
      audioRef.current = audio;
      audio.onended = () => stopSpeech();
      audio.onerror = () => stopSpeech();
      audio.src = url;
      await audio.play();
    } catch (err) {
      stopSpeech();
      // 自动播被策略拦截时不弹窗，用户仍可点 🔊
      if (quiet || err?.name === 'NotAllowedError') return;
      alert(err?.message || '语音播放失败');
    }
  }

  function attachFile(file) {
    if (!file || loading) return false;

    const lower = file.name.toLowerCase();
    const isImage = file.type.startsWith('image/')
      || ['.png', '.jpg', '.jpeg', '.gif', '.webp'].some(ext => lower.endsWith(ext));
    const isDoc = DOC_EXTS.some(ext => lower.endsWith(ext));

    if (isImage) {
      if (file.size > 10 * 1024 * 1024) {
        alert('图片不能超过 10MB');
        return false;
      }
      clearPendingDoc();
      if (pendingImage?.previewUrl) URL.revokeObjectURL(pendingImage.previewUrl);
      setPendingImage({ file, previewUrl: URL.createObjectURL(file) });
      return true;
    }

    if (isDoc) {
      if (file.size > 50 * 1024 * 1024) {
        alert('文件不能超过 50MB');
        return false;
      }
      clearPendingImage();
      setPendingDoc({ file, name: file.name });
      return true;
    }

    alert('仅支持图片或 pdf / doc / docx / txt');
    return false;
  }

  function handlePickAttach(e) {
    const file = e.target.files?.[0];
    if (file) attachFile(file);
    e.target.value = '';
  }

  function isFileDrag(e) {
    const types = e.dataTransfer?.types;
    if (!types) return false;
    // DOMStringList / 部分环境大小写不一致
    return Array.from(types).some(t => String(t).toLowerCase() === 'files');
  }

  function handleChatDragEnter(e) {
    if (!isFileDrag(e)) return;
    e.preventDefault();
    e.stopPropagation();
    if (loading) return;
    fileDragDepthRef.current += 1;
    setFileDragOver(true);
  }

  function handleChatDragLeave(e) {
    if (!isFileDrag(e)) return;
    e.preventDefault();
    e.stopPropagation();
    fileDragDepthRef.current = Math.max(0, fileDragDepthRef.current - 1);
    if (fileDragDepthRef.current === 0) setFileDragOver(false);
  }

  function handleChatDragOver(e) {
    if (!isFileDrag(e)) return;
    e.preventDefault();
    e.stopPropagation();
    if (loading) {
      e.dataTransfer.dropEffect = 'none';
      return;
    }
    e.dataTransfer.dropEffect = 'copy';
  }

  function handleChatDrop(e) {
    e.preventDefault();
    e.stopPropagation();
    fileDragDepthRef.current = 0;
    setFileDragOver(false);
    if (loading) return;
    const file = e.dataTransfer?.files?.[0];
    if (file) attachFile(file);
  }

  // Tauri/浏览器：全局 preventDefault，否则松手会变成“用浏览器打开文件”
  useEffect(() => {
    const block = (e) => {
      if (!isFileDrag(e)) return;
      e.preventDefault();
    };
    window.addEventListener('dragover', block);
    window.addEventListener('drop', block);
    return () => {
      window.removeEventListener('dragover', block);
      window.removeEventListener('drop', block);
    };
  }, []);

  async function handleOpenAttachment(att) {
    if (!att?.url || filePreviewBusy) return;
    if (att.type === 'image') {
      setFilePreview({
        fileName: att.fileName || '图片',
        imageUrl: api.resolveMediaUrl(att.url)
      });
      return;
    }
    setFilePreviewBusy(true);
    try {
      const result = await api.openAttachment(att);
      if (result.mode === 'text') {
        setFilePreview({ fileName: result.fileName, text: result.text });
      }
    } catch (e) {
      alert(e?.message || '打开附件失败');
    } finally {
      setFilePreviewBusy(false);
    }
  }

  async function handleConfirmCard(sessionId, confirmed) {
    if (!sessionId || confirmBusyId) return;
    setConfirmBusyId(sessionId);
    try {
      const verb = confirmed ? '确认' : '取消';
      const r = await api.sendMessageToSession(sessionId, verb);
      const clean = api.stripAssistantMarkers(r.reply || '');
      const cached = api.getCachedSessionMessages(sessionId);
      const next = [
        ...cached,
        { role: 'user', text: verb },
        {
          role: 'assistant',
          text: clean,
          finishReason: r.finishReason || 'STOP',
          attachments: Array.isArray(r.attachments) && r.attachments.length
            ? r.attachments
            : undefined
        }
      ];
      api.cacheSessionMessages(sessionId, next);
      if (r.finishReason === 'NEED_CONFIRM') {
        upsertPendingConfirm(sessionId, clean, r.confirmThreadId);
      } else {
        clearPendingConfirm(sessionId);
      }
      if (sessionId === currentIdRef.current) {
        setMessages(next);
      }
    } catch (e) {
      alert(e?.message || '确认操作失败');
    } finally {
      setConfirmBusyId(null);
    }
  }

  async function handleSend(textOverride) {
    const msg = (typeof textOverride === 'string' ? textOverride : input).trim();
    if (loading) return;
    if (!msg && !pendingImage && !pendingDoc) return;

    // 未登录且本会话用户消息已满 3 条：拦截第 4 次发送，保留输入并弹登录
    if (!api.isLoggedIn()) {
      const userTurns = messages.filter(m => m.role === 'user').length;
      if (userTurns >= FREE_USER_TURNS) {
        if (typeof textOverride === 'string') setInput(msg);
        pendingSendAfterLoginRef.current = true;
        openLogin();
        return;
      }
    }
    pendingSendAfterLoginRef.current = false;

    const imageToSend = pendingImage;
    const docToSend = pendingDoc;
    const sessionIdForSend = api.getCurrentSessionId();
    setInput('');
    setPendingImage(null);
    setPendingDoc(null);
    if (attachInputRef.current) attachInputRef.current.value = '';

    // 取消上一轮未完成的流，并使进行中的 history 回写失效
    streamAbortRef.current?.abort();
    reasonAbortRef.current?.abort();
    historyGenRef.current += 1;
    const ac = new AbortController();
    streamAbortRef.current = ac;
    const reasonAc = new AbortController();
    reasonAbortRef.current = reasonAc;
    const gen = ++streamGenRef.current;
    const bubbleId = `stream-${gen}`;
    streamBubbleIdRef.current = bubbleId;
    streamActiveRef.current = true;
    unlockAudioForAutoplay();

    const writeReason = (updater) => {
      const prev = reasonBySessionRef.current[sessionIdForSend] || { steps: [], ctx: null };
      const next = typeof updater === 'function' ? updater(prev) : updater;
      reasonBySessionRef.current[sessionIdForSend] = next;
      if (currentIdRef.current === sessionIdForSend) {
        setReasonSteps(next?.steps || []);
        setReasonCtx(next?.ctx || null);
      }
      return next;
    };

    // 串行叙述，避免并行打爆 LLM，并保证收束段一定排到队尾执行
    let narrateTail = Promise.resolve();
    const enqueueNarrate = (pending) => {
      if (!pending?.id) return;
      narrateTail = narrateTail.then(async () => {
        if (gen !== streamGenRef.current) return;
        if (reasonAc.signal.aborted) {
          writeReason((prev) => fillStepText(
            prev,
            pending.id,
            pending.phase === 'finish' ? '本轮已收束。' : '阶段推进。',
            'done',
          ));
          return;
        }
        const bundle = reasonBySessionRef.current[sessionIdForSend] || { steps: [], ctx: null };
        const recent = recentTitles(bundle.steps, 6);
        const hint = factsHint(bundle.ctx);
        const shortFallback =
          pending.phase === 'start' ? '先对齐目标再动手。'
            : pending.phase === 'finish' ? '本轮按目标收束，可直接看结论与附件。'
              : '阶段推进。';
        try {
          const text = await api.narrateReason({
            userText: msg,
            phase: pending.phase,
            eventText: pending.eventText,
            recentLines: recent,
            factsHint: hint,
          }, {
            signal: reasonAc.signal,
            onDelta: (_chunk, full) => {
              if (gen !== streamGenRef.current) return;
              const shown = String(full || '').replace(/\s+/g, ' ').trim();
              if (!shown) return;
              writeReason((prev) => fillStepText(prev, pending.id, shown, 'running'));
            },
          });
          if (gen !== streamGenRef.current) return;
          writeReason((prev) => fillStepText(
            prev,
            pending.id,
            text || shortFallback,
            'done',
          ));
        } catch (err) {
          if (gen !== streamGenRef.current) return;
          // Abort 也要落字，否则会永久停在「正在理清这一段…」
          writeReason((prev) => fillStepText(prev, pending.id, shortFallback, 'done'));
        }
      }).catch(() => { /* 队列不中断 */ });
    };

    // 本轮一开始就记入思维链（面板收起也写）；独白走 LLM
    const seeded = writeReason(seedTurnSteps(msg));
    setReasonActive(true);
    enqueueNarrate({
      id: seeded.steps?.[0]?.id,
      phase: 'start',
      eventText: '用户刚发出任务，开始拆解',
    });

    const appendDelta = (chunk) => {
      if (gen !== streamGenRef.current) return;
      if (currentIdRef.current !== sessionIdForSend) return;
      setProgressText('');
      const after = writeReason((prev) => applyReplyStarted(prev));
      if (after?.pending) enqueueNarrate(after.pending);
      setMessages(prev => {
        const idx = prev.findIndex(m => m.id === bubbleId);
        if (idx >= 0) {
          const next = [...prev];
          const cur = next[idx];
          next[idx] = { ...cur, text: (cur.text || '') + chunk, streaming: true };
          return next;
        }
        return [...prev, { id: bubbleId, role: 'assistant', text: chunk, streaming: true }];
      });
      setLoading(false);
    };

    const userWantsVoice = /语音|读给我听|读出来|念给我听|说给我听|用嘴说|播报|用语音|讲出来|用说的|朗读/.test(msg);

    const finishStream = (finishReason, fullReply, attachments, confirmThreadId, route) => {
      if (gen !== streamGenRef.current) return;
      streamActiveRef.current = false;
      streamBubbleIdRef.current = null;
      setProgressText('');
      const routePayload = route || pickRouteFromAttachments(attachments);
      if (routePayload) setLastRoute(routePayload);
      // 优先用 SSE 汇总全文（比从 state 回读更稳）
      const raw = (fullReply != null && fullReply !== '')
        ? fullReply
        : null;
      let parsed = raw != null ? api.parseVoiceTags(raw) : null;
      const atts = Array.isArray(attachments) && attachments.length ? attachments : undefined;
      if (parsed == null) parsed = api.parseVoiceTags('');
      const cleanText = api.stripFileMarkers(parsed.text || '');

      const assistantMsg = {
        id: bubbleId,
        role: 'assistant',
        text: cleanText,
        voice: parsed.voice || undefined,
        attachments: atts,
        finishReason: finishReason || 'STOP'
      };

      if (currentIdRef.current === sessionIdForSend) {
        setMessages(prev => {
          const next = [...prev];
          const idx = next.findIndex(m => m.id === bubbleId);
          if (idx >= 0) next[idx] = assistantMsg;
          else next.push(assistantMsg);
          return next;
        });
      } else {
        const cached = api.getCachedSessionMessages(sessionIdForSend);
        const withoutBubble = cached.filter(m => m.id !== bubbleId);
        api.cacheSessionMessages(sessionIdForSend, [...withoutBubble, assistantMsg]);
      }

      // 仅以后端明确信号为准，避免文案误匹配导致「确认两次」
      const needsConfirm = finishReason === 'NEED_CONFIRM' || !!confirmThreadId;
      if (needsConfirm) {
        upsertPendingConfirm(sessionIdForSend, cleanText, confirmThreadId);
      } else {
        clearPendingConfirm(sessionIdForSend);
      }

      setSessions(api.getSessionList());
      setLoading(false);
      {
        const next = writeReason((prev) => applyTurnFinished(prev, true, cleanText, atts));
        if (next?.pending) enqueueNarrate(next.pending);
      }
      if (currentIdRef.current === sessionIdForSend) {
        setReasonActive(false);
        requestAnimationFrame(() => textareaRef.current?.focus());
      }
      // 自动播报：全局开关打开，或用户本句明确要求语音
      const speakBody = cleanText;
      if (speakBody && (autoVoiceRef.current || userWantsVoice)
          && currentIdRef.current === sessionIdForSend) {
        playSpeech(bubbleId, speakBody, parsed?.voice, { quiet: true });
      }
    };

    const failStream = (text) => {
      if (gen !== streamGenRef.current) return;
      streamActiveRef.current = false;
      streamBubbleIdRef.current = null;
      setProgressText('');
      {
        const next = writeReason((prev) => applyTurnFinished(prev, false, text));
        if (next?.pending) enqueueNarrate(next.pending);
      }
      if (currentIdRef.current === sessionIdForSend) {
        setReasonActive(false);
        setMessages(prev => {
          const without = prev.filter(m => m.id !== bubbleId);
          return [...without, { role: 'assistant', text }];
        });
      }
      setLoading(false);
      if (currentIdRef.current === sessionIdForSend) {
        requestAnimationFrame(() => textareaRef.current?.focus());
      }
    };

    const handlers = {
      signal: ac.signal,
      onProgress: (text) => {
        if (gen !== streamGenRef.current) return;
        const next = writeReason((prev) => applyProgressToSteps(prev, text));
        if (next?.pending) enqueueNarrate(next.pending);
        if (currentIdRef.current !== sessionIdForSend) return;
        setProgressText(text);
      },
      onDelta: (chunk) => appendDelta(chunk)
    };

    if (imageToSend) {
      setMessages(prev => [...prev, {
        role: 'user',
        text: msg,
        imageUrl: imageToSend.previewUrl
      }]);
      setLoading(true);
      try {
        const r = await api.sendImageStream(imageToSend.file, msg, handlers);
        finishStream(r.finishReason, r.reply, r.attachments, r.confirmThreadId, r.route);
      } catch (err) {
        if (err?.name === 'AbortError') {
          streamActiveRef.current = false;
          writeReason((prev) => applyTurnFinished(prev, false, '已中断'));
          if (currentIdRef.current === sessionIdForSend) setReasonActive(false);
          return;
        }
        failStream(`图片发送失败：${err?.message || '请重试'}`);
      }
      return;
    }

    if (docToSend) {
      setMessages(prev => [...prev, {
        role: 'user',
        text: msg,
        fileName: docToSend.name
      }]);
      setLoading(true);
      try {
        const r = await api.sendFileStream(docToSend.file, msg, handlers);
        finishStream(r.finishReason, r.reply, r.attachments, r.confirmThreadId, r.route);
      } catch (err) {
        if (err?.name === 'AbortError') {
          streamActiveRef.current = false;
          writeReason((prev) => applyTurnFinished(prev, false, '已中断'));
          if (currentIdRef.current === sessionIdForSend) setReasonActive(false);
          return;
        }
        failStream(`文档发送失败：${err?.message || '请重试'}`);
      }
      return;
    }

    setMessages(prev => [...prev, { role: 'user', text: msg }]);
    setProgressText('');
    setLoading(true);
    try {
      const r = await api.sendMessageStream(msg, handlers);
      finishStream(r.finishReason, r.reply, r.attachments, r.confirmThreadId, r.route);
    } catch (err) {
      if (err?.name === 'AbortError') {
        streamActiveRef.current = false;
        writeReason((prev) => applyTurnFinished(prev, false, '已中断'));
        if (currentIdRef.current === sessionIdForSend) setReasonActive(false);
        return;
      }
      failStream('网络错误，请重试');
    }
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  function toggleReasonRail() {
    setReasonRailOpen((prev) => {
      const next = !prev;
      localStorage.setItem('reasonRailOpen', next ? '1' : '0');
      return next;
    });
  }

  function switchTo(id) {
    streamAbortRef.current?.abort();
    reasonAbortRef.current?.abort();
    streamGenRef.current += 1;
    streamActiveRef.current = false;
    streamBubbleIdRef.current = null;
    setLoading(false);
    setProgressText('');
    setReasonActive(false);
    setReasonSteps(reasonBySessionRef.current[id]?.steps || []);
    setReasonCtx(reasonBySessionRef.current[id]?.ctx || null);
    api.switchSession(id);
    setCurrentId(id);
    setMainView('chat');
  }

  function newChat() {
    streamAbortRef.current?.abort();
    reasonAbortRef.current?.abort();
    streamGenRef.current += 1;
    streamActiveRef.current = false;
    streamBubbleIdRef.current = null;
    setLoading(false);
    setReasonActive(false);
    const id = api.newSession();
    setSessions(api.getSessionList());
    setCurrentId(id);
    setMessages([]);
    setReasonSteps(reasonBySessionRef.current[id]?.steps || []);
    setReasonCtx(reasonBySessionRef.current[id]?.ctx || null);
    clearPendingImage();
    clearPendingDoc();
    syncConfirmUi(id);
    setMainView('chat');
  }

  function delSession(id, e) {
    e.stopPropagation();
    setDeleteClosing(false);
    setDeleteConfirmId(id);
  }

  function closeDeleteConfirm() {
    if (!deleteConfirmId || deleteClosing) return;
    setDeleteClosing(true);
  }

  function handleDeleteAnimEnd(e) {
    if (e.target !== e.currentTarget) return;
    if (deleteClosing) {
      setDeleteConfirmId(null);
      setDeleteClosing(false);
    }
  }

  function confirmDeleteSession() {
    const id = deleteConfirmId;
    if (!id) return;
    api.deleteSession(id);
    const list = api.getSessionList();
    setSessions(list);
    setDeleteClosing(false);
    setDeleteConfirmId(null);
    if (id === currentId) {
      const next = list[0]?.id;
      if (next) { switchTo(next); } else { newChat(); }
    }
  }

  function openSessionMenu(e, session) {
    e.preventDefault();
    e.stopPropagation();
    setCtxMenu({
      x: e.clientX,
      y: e.clientY,
      id: session.id,
      pinned: !!session.pinned,
    });
  }

  function startRename(id) {
    const s = sessions.find(x => x.id === id);
    setRenamingId(id);
    setRenameValue(s?.title || '');
    setCtxMenu(null);
  }

  useEffect(() => {
    if (renamingId) {
      renameInputRef.current?.focus();
      renameInputRef.current?.select();
    }
  }, [renamingId]);

  function commitRename() {
    if (!renamingId) return;
    const ok = api.renameSession(renamingId, renameValue);
    if (ok) setSessions(api.getSessionList());
    setRenamingId(null);
    setRenameValue('');
  }

  function cancelRename() {
    setRenamingId(null);
    setRenameValue('');
  }

  function handleTogglePin(id) {
    api.togglePinSession(id);
    setSessions(api.getSessionList());
    setCtxMenu(null);
  }

  useEffect(() => {
    if (!ctxMenu) return undefined;
    const close = () => setCtxMenu(null);
    const onKey = (e) => { if (e.key === 'Escape') close(); };
    window.addEventListener('click', close);
    window.addEventListener('scroll', close, true);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('click', close);
      window.removeEventListener('scroll', close, true);
      window.removeEventListener('keydown', onKey);
    };
  }, [ctxMenu]);

  const canSend = !loading && (!!input.trim() || !!pendingImage || !!pendingDoc);
  const placeholder = pendingImage
    ? '添加说明（可选）…'
    : pendingDoc
      ? '问点关于文档的问题（可选）…'
      : '发送消息…';

  const sessionListNodes = (keyPrefix) => sessions.map(s => (
    <div
      key={`${keyPrefix}-${s.id}`}
      className={`session-item ${s.id === currentId ? 'active' : ''} ${s.pinned ? 'pinned' : ''}`}
      onClick={() => { if (renamingId !== s.id) switchTo(s.id); }}
      onContextMenu={(e) => openSessionMenu(e, s)}
    >
      {s.pinned && <span className="session-pin" title="已置顶" aria-hidden="true" />}
      {renamingId === s.id && keyPrefix === 'main' ? (
        <input
          ref={renameInputRef}
          className="session-rename-input"
          value={renameValue}
          maxLength={30}
          onChange={e => setRenameValue(e.target.value)}
          onClick={e => e.stopPropagation()}
          onBlur={commitRename}
          onKeyDown={e => {
            if (e.key === 'Enter') {
              e.preventDefault();
              commitRename();
            } else if (e.key === 'Escape') {
              e.preventDefault();
              cancelRename();
            }
          }}
        />
      ) : (
        <span className="session-title">{s.title}</span>
      )}
      <button className="del-btn" onClick={e => delSession(s.id, e)}>×</button>
    </div>
  ));

  return (
    <div className="app">
      {/* 主侧栏始终在文档流里做 width 匀速动画 */}
      <div className="sidebar-shell">
        <aside className={`sidebar ${sidebarOpen ? '' : 'collapsed'}`}>
          <div
            className={`sidebar-header ${sidebarToggleReveal ? 'reveal-toggle' : ''}`}
            onMouseLeave={() => setSidebarToggleReveal(false)}
          >
            <button className="new-chat-btn" onClick={newChat}>
              <img className="new-chat-icon" src={newChatIcon} alt="" aria-hidden="true" />
              新对话
            </button>
            <button
              className="toggle-btn"
              onClick={collapseSidebar}
              tabIndex={sidebarToggleReveal ? 0 : -1}
              aria-hidden={!sidebarToggleReveal}
            >
              ☰
            </button>
            <div
              className="toggle-hotzone"
              aria-hidden="true"
              onMouseEnter={() => setSidebarToggleReveal(true)}
            />
          </div>
          <div className="session-list">
            {sessionListNodes('main')}
          </div>
          {loggedIn && (
            <div className="sidebar-user-wrap">
              {renderUserMenu()}
              <button
                type="button"
                className="sidebar-user"
                title={api.getDisplayName()}
                onClick={toggleUserMenu}
                aria-haspopup="menu"
                aria-expanded={userMenuOpen}
              >
                {api.getDisplayName()}
              </button>
            </div>
          )}
        </aside>
      </div>

      {/* 收起后的打开按钮 + 下方预览浮层（与主侧栏分离，避免打断匀速展开） */}
      {!sidebarOpen && (
        <div
          className="sidebar-float-host"
          onMouseLeave={() => setSidebarPeek(false)}
        >
          <button
            className="open-sidebar-btn visible"
            onMouseEnter={() => setSidebarPeek(true)}
            onClick={expandSidebar}
            aria-label="打开侧栏"
          >
            ☰
          </button>
          {sidebarPeek && (
            <aside className="sidebar peek-panel">
              <div className="sidebar-header">
                <button className="new-chat-btn" onClick={newChat}>
              <img className="new-chat-icon" src={newChatIcon} alt="" aria-hidden="true" />
              新对话
            </button>
              </div>
              <div className="session-list">
                {sessionListNodes('peek')}
              </div>
              {loggedIn && (
                <div className="sidebar-user-wrap">
                  {renderUserMenu()}
                  <button
                    type="button"
                    className="sidebar-user"
                    title={api.getDisplayName()}
                    onClick={toggleUserMenu}
                    aria-haspopup="menu"
                    aria-expanded={userMenuOpen}
                  >
                    {api.getDisplayName()}
                  </button>
                </div>
              )}
            </aside>
          )}
        </div>
      )}

      {ctxMenu && (
        <div
          className="session-ctx-menu"
          style={{ left: ctxMenu.x, top: ctxMenu.y }}
          role="menu"
          onClick={e => e.stopPropagation()}
          onContextMenu={e => e.preventDefault()}
        >
          <button type="button" role="menuitem" onClick={() => startRename(ctxMenu.id)}>
            重命名
          </button>
          <button type="button" role="menuitem" onClick={() => handleTogglePin(ctxMenu.id)}>
            {ctxMenu.pinned ? '取消置顶' : '置顶'}
          </button>
        </div>
      )}

      <main
        className={`main ${fileDragOver && mainView === 'chat' ? 'file-drag-over' : ''}`}
        onDragEnter={mainView === 'chat' ? handleChatDragEnter : undefined}
        onDragLeave={mainView === 'chat' ? handleChatDragLeave : undefined}
        onDragOver={mainView === 'chat' ? handleChatDragOver : undefined}
        onDrop={mainView === 'chat' ? handleChatDrop : undefined}
      >
        {mainView === 'usage' ? (
          <div className="usage-page">
            <header className="usage-topbar">
              <button type="button" className="usage-back-btn" onClick={backToChat}>
                ← 返回对话
              </button>
              <div className="usage-title-block">
                <h1 className="usage-title">使用统计</h1>
                <p className="usage-subtitle">Token、耗时与请求数</p>
              </div>
              <button
                type="button"
                className="usage-refresh-btn"
                onClick={loadUsageStats}
                disabled={usageLoading}
              >
                {usageLoading ? '刷新中…' : '刷新'}
              </button>
            </header>
            <div className="usage-body">
              {usageError && <div className="usage-error">{usageError}</div>}
              {!usageError && usageLoading && !usageData && (
                <div className="usage-empty">加载中…</div>
              )}
              {usageData && (
                <>
                  <div className="usage-summary">
                    {[
                      ['对话轮次', formatNum(usageData.summary?.totalTurns)],
                      ['LLM 请求', formatNum(usageData.summary?.totalLlmCalls)],

                      ['工具调用', formatNum(usageData.summary?.totalToolCalls)],
                      ['总 Token', formatNum(usageData.summary?.totalTokens)],
                      ['输入 Token', formatNum(usageData.summary?.totalPromptTokens)],
                      ['输出 Token', formatNum(usageData.summary?.totalCompletionTokens)],
                      ['平均耗时', formatMs(usageData.summary?.avgLatencyMs)],
                      ['均 Token/轮', formatNum(usageData.summary?.avgTokensPerTurn)],
                    ].map(([label, value]) => (
                      <div key={label} className="usage-stat">
                        <div className="usage-stat-value">{value}</div>
                        <div className="usage-stat-label">{label}</div>
                      </div>
                    ))}
                  </div>
                  {usageData.note && <p className="usage-note">{usageData.note}</p>}
                  <h2 className="usage-section-title">近期趋势</h2>
                  <UsageTrendChart
                    turns={usageData.recentTurns || []}
                    formatNum={formatNum}
                    formatMs={formatMs}
                  />
                </>
              )}
            </div>
          </div>
        ) : mainView === 'routeMap' ? (
          <RouteMapView onBack={backToChat} />
        ) : (
        <>
        {fileDragOver && (
          <div className="file-drop-overlay" aria-hidden="true">
            <div className="file-drop-overlay-inner">松开以上传图片或文档</div>
          </div>
        )}
        <header className="main-topbar">
          <div className="chat-title-bar">
            <span className="chat-title">
              {(sessions.find(s => s.id === currentId)?.title || '').trim() || '新对话'}
            </span>
            <span className="chat-title-hint">AI 生成可能有误 注意核实</span>
          </div>
          {!loggedIn && (
            <button
              type="button"
              className="login-btn"
              onClick={openLogin}
            >
              登录
            </button>
          )}
          {loggedIn && (
            <button
              type="button"
              className={`voice-toggle-btn ${autoVoice ? 'is-on' : 'is-off'}`}
              onClick={toggleAutoVoice}
              title={autoVoice ? '自动播报已开：点击关闭' : '自动播报已关：点击开启'}
              aria-pressed={autoVoice}
              aria-label={autoVoice ? '关闭自动语音播报' : '开启自动语音播报'}
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M11 5L6 9H3v6h3l5 4V5z"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinejoin="round"
                  fill="currentColor"
                />
                <path
                  d="M15.5 8.5a5 5 0 0 1 0 7"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  fill="none"
                />
                <path
                  d="M18 6a8 8 0 0 1 0 12"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  fill="none"
                />
                {!autoVoice && (
                  <path
                    d="M4 4l16 16"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                  />
                )}
              </svg>
            </button>
          )}
        </header>

        {messages.length === 0 && !loading ? (
          <div className="welcome">
            <h1 aria-label={WELCOME_PROMPT}>
              {welcomeShown}
              {welcomeShown.length < WELCOME_PROMPT.length && (
                <span className="welcome-cursor" aria-hidden="true" />
              )}
            </h1>
            <div className="welcome-suggestions" aria-label="推荐提问">
              {newsLoading ? (
                <>
                  <div className="welcome-chip-row" aria-hidden="true">
                    {[0, 1, 2].map(i => (
                      <span key={`sk-1-${i}`} className="welcome-chip-skeleton welcome-chip-skeleton-news" />
                    ))}
                  </div>
                  <div className="welcome-chip-row" aria-hidden="true">
                    {[0, 1, 2, 3].map(i => (
                      <span key={`sk-2-${i}`} className="welcome-chip-skeleton welcome-chip-skeleton-news" />
                    ))}
                  </div>
                  <div className="welcome-chip-row" aria-hidden="true">
                    {[0, 1, 2].map(i => (
                      <span key={`sk-3-${i}`} className="welcome-chip-skeleton welcome-chip-skeleton-news" />
                    ))}
                  </div>
                </>
              ) : welcomeChips.length > 0 ? (
                <>
                  {[welcomeChips.slice(0, 3), welcomeChips.slice(3, 7), welcomeChips.slice(7, 10)]
                    .filter(row => row.length > 0)
                    .map((row, ri) => (
                      <div key={`row-${ri}`} className="welcome-chip-row">
                        {row.map(chip => (
                          <button
                            key={chip.key}
                            type="button"
                            className={`welcome-chip ${chip.kind === 'news' ? 'welcome-chip-news' : ''}`}
                            title={chip.send}
                            onClick={() => handleSend(chip.send)}
                          >
                            {chip.label}
                          </button>
                        ))}
                      </div>
                    ))}
                </>
              ) : null}
            </div>
          </div>
        ) : (
          <div className="message-list">
            {messages.map((m, i) => {
              const key = m.id || `m-${i}`;
              const displayText = m.role === 'assistant'
                ? api.stripAssistantMarkers(m.text || '')
                : (m.text || '');
              const canSpeak = m.role === 'assistant' && !!displayText && !m.streaming;
              const atts = Array.isArray(m.attachments) ? m.attachments : [];
              return (
                <div key={key} className={`msg-row ${m.role}`}>
                  <div className="msg-body">
                    {canSpeak && (
                      <div className="msg-label-row">
                        <button
                          type="button"
                          className={`speak-btn ${speakingKey === key ? 'busy' : ''}`}
                          title={speakingKey === key ? '播放中…' : '朗读'}
                          disabled={speakingKey === key}
                          onClick={() => playSpeech(key, displayText, m.voice)}
                        >
                          {speakingKey === key ? '…' : '🔊'}
                        </button>
                      </div>
                    )}
                    {m.imageUrl && (
                      <img className="msg-image" src={m.imageUrl} alt="上传图片" />
                    )}
                    {m.fileName && (
                      <div className="msg-file">📄 {m.fileName}</div>
                    )}
                    {atts.map((a, ai) => (
                      a.type === 'image' ? (
                        <button
                          key={`${key}-att-${ai}`}
                          type="button"
                          className="msg-att-image-link"
                          onClick={() => handleOpenAttachment(a)}
                          title="点击查看"
                        >
                          <img className="msg-image" src={a.url} alt={a.fileName || '截图'} />
                        </button>
                      ) : (
                        <button
                          key={`${key}-att-${ai}`}
                          type="button"
                          className="msg-att-file"
                          onClick={() => handleOpenAttachment(a)}
                          disabled={filePreviewBusy}
                          title="点击打开"
                        >
                          📄 {a.fileName || '打开文件'}
                        </button>
                      )
                    ))}
                    {displayText ? (
                      m.role === 'assistant' ? (
                        <div className="msg-text msg-md">
                          <ReactMarkdown remarkPlugins={[remarkGfm]}>
                            {displayText}
                          </ReactMarkdown>
                        </div>
                      ) : (
                        <div className="msg-text">{displayText}</div>
                      )
                    ) : null}
                  </div>
                </div>
              );
            })}
            {loading && (
              <div className="msg-row assistant">
                <div className="msg-body">
                  <div className="msg-text thinking" aria-label={progressText || '正在输入'}>
                    {progressText ? (
                      <span className="progress-label">{progressText}</span>
                    ) : null}
                    <span className="thinking-dots" aria-hidden="true">
                      <span className="dot" />
                      <span className="dot" />
                      <span className="dot" />
                    </span>
                  </div>
                </div>
              </div>
            )}
            <div ref={bottomRef} />
          </div>
        )}

        <div className="input-area">
          {pendingImage && (
            <div className="pending-image">
              <img src={pendingImage.previewUrl} alt="待发送" />
              <button type="button" className="pending-remove" onClick={clearPendingImage}>×</button>
            </div>
          )}
          {pendingDoc && (
            <div className="pending-doc">
              <span>📄 {pendingDoc.name}</span>
              <button type="button" className="pending-remove" onClick={clearPendingDoc}>×</button>
            </div>
          )}
          <div className="input-box">
            <button
              type="button"
              className="attach-btn"
              title="上传图片或文档"
              disabled={loading}
              onClick={() => attachInputRef.current?.click()}
              aria-label="上传图片或文档"
            >
              <svg className="attach-plus" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                <path
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.2"
                  strokeLinecap="round"
                  d="M12 5v14M5 12h14"
                />
              </svg>
            </button>
            <input
              ref={attachInputRef}
              type="file"
              accept="image/*,.pdf,.doc,.docx,.txt,application/pdf,text/plain"
              hidden
              onChange={handlePickAttach}
            />
            <textarea
              ref={textareaRef}
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={placeholder}
              rows={1}
              disabled={loading}
            />
            <button type="button" className="send-btn" onClick={() => handleSend()} disabled={!canSend}>↑</button>
          </div>
          <div className="input-hint">
            {awaitingConfirm
              ? '请回复「确认」或「取消」'
              : 'Enter 发送 · + 或拖拽上传图片/文档'}
          </div>
        </div>
        </>
        )}
      </main>

      <ReasonRail
        open={reasonRailOpen}
        onToggle={toggleReasonRail}
        steps={reasonSteps}
        ctx={reasonCtx}
        active={reasonActive && mainView === 'chat'}
      />

      {filePreview && (
        <div
          className="file-preview-overlay"
          role="dialog"
          aria-modal="true"
          aria-label={filePreview.fileName}
          onClick={() => setFilePreview(null)}
        >
          <div className="file-preview-panel" onClick={e => e.stopPropagation()}>
            <div className="file-preview-header">
              <span className="file-preview-title">📄 {filePreview.fileName}</span>
              <button
                type="button"
                className="file-preview-close"
                onClick={() => setFilePreview(null)}
              >
                ×
              </button>
            </div>
            {filePreview.imageUrl ? (
              <div className="file-preview-image-wrap">
                <img src={filePreview.imageUrl} alt={filePreview.fileName} />
              </div>
            ) : isMarkdownFile(filePreview.fileName) ? (
              <div className="file-preview-body file-preview-md">
                <ReactMarkdown remarkPlugins={[remarkGfm]}>
                  {filePreview.text || ''}
                </ReactMarkdown>
              </div>
            ) : (
              <pre className="file-preview-body">{filePreview.text}</pre>
            )}
          </div>
        </div>
      )}

      {confirmCards.length > 0 && (
        <div className="confirm-toast-stack" aria-live="polite">
          {confirmCards.map(card => {
            const title = sessions.find(s => s.id === card.sessionId)?.title || '对话';
            const busy = confirmBusyId === card.sessionId;
            return (
              <div key={card.sessionId} className="confirm-toast">
                <div className="confirm-toast-label">需要确认 · {title}</div>
                <div className="confirm-toast-desc">{card.description}</div>
                <div className="confirm-toast-actions">
                  <button
                    type="button"
                    className="confirm-toast-btn cancel"
                    disabled={busy}
                    onClick={() => handleConfirmCard(card.sessionId, false)}
                  >
                    取消
                  </button>
                  <button
                    type="button"
                    className="confirm-toast-btn ok"
                    disabled={busy}
                    onClick={() => handleConfirmCard(card.sessionId, true)}
                  >
                    {busy ? '处理中…' : '确认'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {showLogin && (
        <div
          className={`login-overlay ${loginClosing ? 'closing' : ''}`}
          role="dialog"
          aria-modal="true"
          aria-label="登录"
          onClick={closeLogin}
          onAnimationEnd={handleLoginAnimEnd}
        >
          {loginErrorVisible && (
            <div
              className={`login-error-toast ${loginErrorClosing ? 'closing' : ''}`}
              role="alert"
              onAnimationEnd={handleLoginErrorAnimEnd}
            >
              <span className="login-error-icon" aria-hidden="true">!</span>
              <span>{loginErrorMessage}</span>
            </div>
          )}
          <div className="login-panel" onClick={e => e.stopPropagation()}>
            {loginStep === 2 && (
              <button
                type="button"
                className="login-panel-back"
                aria-label="返回"
                onClick={handleLoginBack}
              >
                &lt;
              </button>
            )}
            <button
              type="button"
              className="login-panel-close"
              aria-label="关闭"
              onClick={closeLogin}
            >
              ×
            </button>
            {loginStep === 1 && (
              <h2 className="login-panel-title">登陆以解锁更多功能</h2>
            )}
            {loginStep === 1 ? (
              <>
                <label className="login-field">
                  <span>邮箱</span>
                  <input
                    ref={loginEmailRef}
                    type="email"
                    placeholder="请输入qq邮箱"
                    autoComplete="email"
                    value={loginEmail}
                    onChange={e => {
                      setLoginEmail(e.target.value);
                      if (loginErrorVisible) hideLoginEmailError();
                    }}
                    onKeyDown={e => {
                      if (e.key === 'Enter') {
                        e.preventDefault();
                        handleLoginNext();
                      }
                    }}
                  />
                </label>
                <button
                  type="button"
                  className="login-submit-btn"
                  disabled={!loginEmailHasInput}
                  onClick={handleLoginNext}
                >
                  下一步
                </button>
              </>
            ) : (
              <div className="login-otp-page">
                <h3 className="login-otp-title">输入六位验证码</h3>
                <p className="login-otp-hint">
                  验证码已发送至{loginEmail.trim()}
                </p>
                <div className="login-otp-boxes" onPaste={handleOtpPaste}>
                  {otpDigits.map((digit, i) => (
                    <input
                      key={i}
                      ref={el => { otpRefs.current[i] = el; }}
                      className="login-otp-box"
                      disabled={otpBusy}
                      type="text"
                      inputMode="numeric"
                      autoComplete={i === 0 ? 'one-time-code' : 'off'}
                      maxLength={6}
                      value={digit}
                      aria-label={`验证码第 ${i + 1} 位`}
                      onChange={e => handleOtpChange(i, e.target.value)}
                      onKeyDown={e => handleOtpKeyDown(i, e)}
                    />
                  ))}
                </div>
                {otpCountdown > 0 ? (
                  <p className="login-otp-resend is-wait">
                    重新发送验证码 {otpCountdown}s
                  </p>
                ) : (
                  <button
                    type="button"
                    className="login-otp-resend is-ready"
                    onClick={handleResendLoginCode}
                    disabled={otpBusy}
                  >
                    重新发送
                  </button>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {deleteConfirmId && (
        <div
          className={`login-overlay ${deleteClosing ? 'closing' : ''}`}
          role="dialog"
          aria-modal="true"
          aria-labelledby="delete-confirm-title"
          onClick={closeDeleteConfirm}
          onAnimationEnd={handleDeleteAnimEnd}
        >
          <div className="delete-confirm-panel" onClick={e => e.stopPropagation()}>
            <div className="delete-confirm-body">
              <span className="delete-confirm-icon" aria-hidden="true">!</span>
              <div className="delete-confirm-text">
                <h3 id="delete-confirm-title" className="delete-confirm-title">确定删除对话？</h3>
                <p className="delete-confirm-desc">删除后，聊天记录将不可恢复。</p>
              </div>
            </div>
            <div className="delete-confirm-actions">
              <button type="button" className="delete-confirm-cancel" onClick={closeDeleteConfirm}>
                取消
              </button>
              <button type="button" className="delete-confirm-ok" onClick={confirmDeleteSession}>
                删除
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
