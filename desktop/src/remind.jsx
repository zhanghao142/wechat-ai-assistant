import React, { useEffect, useState } from 'react';
import ReactDOM from 'react-dom/client';
import './remind.css';

function readStoredCards() {
  try {
    const raw = localStorage.getItem('remindPopupPayload');
    const list = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

function RemindApp() {
  const [cards, setCards] = useState(() => readStoredCards());

  useEffect(() => {
    function apply(payload) {
      if (Array.isArray(payload)) setCards(payload);
    }
    apply(readStoredCards());

    let unlisten = () => {};
    (async () => {
      try {
        const { listen, emit } = await import('@tauri-apps/api/event');
        unlisten = await listen('remind-popup-data', (ev) => apply(ev.payload));
        await emit('remind-popup-ready', { t: Date.now() });
      } catch { /* ignore */ }
    })();
    return () => { try { unlisten(); } catch { /* ignore */ } };
  }, []);

  function removeCard(notificationId, sessionId) {
    setCards(prev => {
      const next = prev.filter(c => {
        if (notificationId) return c.notificationId !== notificationId;
        return c.sessionId !== sessionId;
      });
      try { localStorage.setItem('remindPopupPayload', JSON.stringify(next)); } catch { /* ignore */ }
      if (!next.length) {
        import('@tauri-apps/api/webviewWindow').then(({ getCurrentWebviewWindow }) => {
          try { getCurrentWebviewWindow().hide(); } catch { /* ignore */ }
        });
      }
      return next;
    });
  }

  async function dismiss(notificationId, sessionId) {
    removeCard(notificationId, sessionId);
    try {
      const { emit } = await import('@tauri-apps/api/event');
      await emit('remind-popup-dismiss', { notificationId, sessionId, open: false });
    } catch { /* ignore */ }
  }

  async function openChat(notificationId, sessionId) {
    removeCard(notificationId, sessionId);
    try {
      const { emit } = await import('@tauri-apps/api/event');
      await emit('remind-popup-dismiss', { notificationId, sessionId, open: true });
    } catch { /* ignore */ }
  }

  if (!cards.length) {
    return (
      <div className="remind-root">
        <div className="remind-empty">加载中…</div>
      </div>
    );
  }

  return (
    <div className="remind-root">
      {cards.map(card => (
        <div key={card.notificationId || `${card.sessionId}-${card.content}`} className="remind-card">
          <div className="remind-label">提醒 · {card.title || '对话'}</div>
          <div className="remind-desc">{card.content}</div>
          <div className="remind-actions">
            <button
              type="button"
              className="remind-btn"
              onClick={() => dismiss(card.notificationId, card.sessionId)}
            >
              知道了
            </button>
            <button
              type="button"
              className="remind-btn open"
              onClick={() => openChat(card.notificationId, card.sessionId)}
            >
              打开对话
            </button>
          </div>
        </div>
      ))}
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <RemindApp />
  </React.StrictMode>
);
