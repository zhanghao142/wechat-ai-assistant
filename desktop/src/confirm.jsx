import React, { useEffect, useState } from 'react';
import ReactDOM from 'react-dom/client';
import { sendMessageToSession, stripAssistantMarkers, cacheSessionMessages, getCachedSessionMessages } from './api';
import './confirm.css';

function readStoredCards() {
  try {
    const raw = localStorage.getItem('confirmPopupPayload');
    const list = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

function ConfirmApp() {
  const [cards, setCards] = useState(() => readStoredCards());
  const [busyId, setBusyId] = useState(null);

  useEffect(() => {
    function apply(payload) {
      if (Array.isArray(payload)) setCards(payload);
    }
    // 启动时再读一次（主窗口可能刚写入）
    apply(readStoredCards());

    let unlisten = () => {};
    (async () => {
      try {
        const { listen, emit } = await import('@tauri-apps/api/event');
        unlisten = await listen('confirm-popup-data', (ev) => apply(ev.payload));
        await emit('confirm-popup-ready', { t: Date.now() });
      } catch { /* ignore */ }
    })();
    return () => { try { unlisten(); } catch { /* ignore */ } };
  }, []);

  async function resolveCard(sessionId, confirmed) {
    if (!sessionId || busyId) return;
    setBusyId(sessionId);
    try {
      const verb = confirmed ? '确认' : '取消';
      const r = await sendMessageToSession(sessionId, verb);
      const clean = stripAssistantMarkers(r.reply || '');
      const cached = getCachedSessionMessages(sessionId);
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
      cacheSessionMessages(sessionId, next);

      const { emit } = await import('@tauri-apps/api/event');
      await emit('confirm-popup-resolved', {
        sessionId,
        confirmed,
        finishReason: r.finishReason || 'STOP',
        confirmThreadId: r.confirmThreadId || null,
        reply: clean,
        attachments: r.attachments || []
      });

      setCards(prev => {
        let nextCards;
        if (r.finishReason === 'NEED_CONFIRM') {
          nextCards = prev.map(c => c.sessionId === sessionId
            ? { ...c, description: clean, confirmThreadId: r.confirmThreadId || null }
            : c);
        } else {
          nextCards = prev.filter(c => c.sessionId !== sessionId);
        }
        try { localStorage.setItem('confirmPopupPayload', JSON.stringify(nextCards)); } catch { /* ignore */ }
        if (!nextCards.length) {
          import('@tauri-apps/api/webviewWindow').then(({ getCurrentWebviewWindow }) => {
            try { getCurrentWebviewWindow().hide(); } catch { /* ignore */ }
          });
        }
        return nextCards;
      });
    } catch (e) {
      alert(e?.message || '确认操作失败');
    } finally {
      setBusyId(null);
    }
  }

  if (!cards.length) {
    return (
      <div className="confirm-root">
        <div className="confirm-empty">加载中…</div>
      </div>
    );
  }

  return (
    <div className="confirm-root">
      {cards.map(card => {
        const busy = busyId === card.sessionId;
        return (
          <div key={card.sessionId} className="confirm-card">
            <div className="confirm-label">需要确认 · {card.title || '对话'}</div>
            <div className="confirm-desc">{card.description}</div>
            <div className="confirm-actions">
              <button
                type="button"
                className="confirm-btn cancel"
                disabled={busy}
                onClick={() => resolveCard(card.sessionId, false)}
              >
                取消
              </button>
              <button
                type="button"
                className="confirm-btn ok"
                disabled={busy}
                onClick={() => resolveCard(card.sessionId, true)}
              >
                {busy ? '处理中…' : '确认'}
              </button>
            </div>
          </div>
        );
      })}
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ConfirmApp />
  </React.StrictMode>
);
