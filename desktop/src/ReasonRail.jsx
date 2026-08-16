import { useEffect, useMemo, useRef } from 'react';
import { workloadHint } from './reasonTrace';
import './ReasonRail.css';

/**
 * 右侧思维链：连贯工作叙述（散文），不做独白/查证/判断卡片。
 */
export default function ReasonRail({ open, onToggle, steps, active, ctx }) {
  const list = Array.isArray(steps) ? steps : [];
  const listRef = useRef(null);

  const paragraphs = useMemo(
    () => list.filter((s) => !s.silent && s.phase !== 'live'),
    [list],
  );
  const live = useMemo(
    () => [...list].reverse().find((s) => s.silent && s.status === 'running'),
    [list],
  );
  const work = workloadHint(ctx) || '';

  useEffect(() => {
    if (!open || !listRef.current) return;
    listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [open, paragraphs.length, live?.title, active]);

  return (
    <div className={`reason-rail-shell ${open ? 'is-open' : 'is-closed'}`}>
      <button
        type="button"
        className={`reason-rail-toggle ${open ? 'is-open' : ''} ${active ? 'is-active' : ''}`}
        onClick={onToggle}
        title={open ? '收起思维链' : '展开思维链'}
        aria-expanded={open}
        aria-controls="reason-rail-panel"
      >
        <span className="reason-rail-toggle-icon" aria-hidden="true">
          {open ? '›' : '‹'}
        </span>
        <span className="reason-rail-toggle-label">思维链</span>
        {active && <span className="reason-rail-live-dot" aria-hidden="true" />}
        {!open && paragraphs.length > 0 && (
          <span className="reason-rail-badge">{paragraphs.length}</span>
        )}
      </button>

      <aside
        id="reason-rail-panel"
        className={`reason-rail ${open ? '' : 'collapsed'}`}
        aria-hidden={!open}
      >
        <header className="reason-rail-header">
          <div className="reason-rail-title-block">
            <h2 className="reason-rail-title">工作叙述</h2>
            {active ? (
              <p className="reason-rail-sub">推演中…</p>
            ) : paragraphs.length > 0 ? (
              <p className="reason-rail-sub">本轮已收束</p>
            ) : null}
          </div>
          {work ? <p className="reason-rail-workload">{work}</p> : null}
        </header>

        <div className="reason-rail-list" ref={listRef}>
          {paragraphs.length === 0 && !live ? (
            <div className="reason-rail-empty">
              复杂任务会在这里用连贯叙述写出推理与实际工作量：为何这样查、读到什么改变了判断、何时停搜去交付——不是标签卡片，也不是进度复读。
            </div>
          ) : (
            <div className="reason-essay">
              {paragraphs.map((s, i) => {
                const waiting = s.status === 'running' && (!s.title || s.title === '…');
                return (
                  <p
                    key={s.id || i}
                    className={`reason-essay-p ${s.status === 'running' ? 'is-running' : ''} ${s.status === 'error' ? 'is-error' : ''}`}
                  >
                    {waiting ? (
                      <span className="reason-essay-caret">正在理清这一段…</span>
                    ) : (
                      s.title
                    )}
                    {s.status === 'running' && s.title && s.title !== '…' ? (
                      <span className="reason-essay-blink" aria-hidden="true" />
                    ) : null}
                  </p>
                );
              })}
              {live ? (
                <p className="reason-essay-live">
                  <span className="reason-spin" aria-hidden="true" />
                  {live.title || '处理中…'}
                </p>
              ) : null}
            </div>
          )}
        </div>
      </aside>
    </div>
  );
}
