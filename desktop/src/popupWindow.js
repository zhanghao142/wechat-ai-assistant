/**
 * 系统级小窗公共逻辑：定位、创建、等页面就绪再推数据。
 * Windows 下 transparent 易导致只见边框无内容，故禁用透明。
 */

export function isTauri() {
  return typeof window !== 'undefined'
    && !!(window.__TAURI_INTERNALS__ || window.__TAURI__);
}

const MARGIN = 20;
const TASKBAR_PAD = 48;

/**
 * @param {object} opts
 * @param {string} opts.label
 * @param {string} opts.url
 * @param {string} opts.title
 * @param {string} opts.storageKey
 * @param {string} opts.dataEvent
 * @param {string} opts.readyEvent
 * @param {number} opts.width
 * @param {number} opts.height
 * @param {number} [opts.yOffset]
 * @param {unknown[]} opts.payload
 */
export async function syncPopupWindow(opts) {
  if (!isTauri()) return false;

  const {
    label,
    url,
    title,
    storageKey,
    dataEvent,
    readyEvent,
    width,
    height,
    yOffset = 0,
    payload
  } = opts;

  const list = Array.isArray(payload) ? payload : [];
  try {
    localStorage.setItem(storageKey, JSON.stringify(list));
  } catch { /* ignore */ }

  const { emit, emitTo, once } = await import('@tauri-apps/api/event');
  const { WebviewWindow } = await import('@tauri-apps/api/webviewWindow');
  const { LogicalPosition, LogicalSize } = await import('@tauri-apps/api/dpi');
  const { primaryMonitor } = await import('@tauri-apps/api/window');

  if (!list.length) {
    const existing = await WebviewWindow.getByLabel(label);
    if (existing) {
      try { await existing.hide(); } catch { /* ignore */ }
    }
    try { await emit(dataEvent, list); } catch { /* ignore */ }
    return true;
  }

  let win = await WebviewWindow.getByLabel(label);
  let createdNow = false;
  if (!win) {
    createdNow = true;
    win = new WebviewWindow(label, {
      url,
      title,
      width,
      height,
      decorations: false,
      transparent: false,
      backgroundColor: [255, 255, 255, 255],
      alwaysOnTop: true,
      skipTaskbar: true,
      resizable: false,
      focus: false,
      visible: false,
      shadow: true
    });
    try {
      await new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error(`${label} 创建超时`)), 8000);
        win.once('tauri://created', () => { clearTimeout(t); resolve(); });
        win.once('tauri://error', (e) => { clearTimeout(t); reject(e); });
      });
    } catch (err) {
      console.warn(`[${label}] create failed`, err);
      win = await WebviewWindow.getByLabel(label);
      if (!win) throw err;
    }
  }

  try {
    await win.setSize(new LogicalSize(width, height));
  } catch { /* ignore */ }

  try {
    const monitor = await primaryMonitor();
    if (monitor) {
      const scale = monitor.scaleFactor || 1;
      const screenW = monitor.size.width / scale;
      const screenH = monitor.size.height / scale;
      const originX = monitor.position.x / scale;
      const originY = monitor.position.y / scale;
      const x = Math.round(originX + screenW - width - MARGIN);
      const y = Math.round(originY + screenH - height - MARGIN - TASKBAR_PAD - yOffset);
      await win.setPosition(new LogicalPosition(x, y));
    }
  } catch (e) {
    console.warn(`[${label}] position failed`, e);
  }

  // 等子弹页 ready（最长 2s），再推数据，避免空壳边框
  if (createdNow) {
    try {
      await Promise.race([
        once(readyEvent),
        new Promise(r => setTimeout(r, 2000))
      ]);
    } catch { /* ignore */ }
  }

  async function pushData() {
    try {
      await emitTo(label, dataEvent, list);
    } catch {
      try { await emit(dataEvent, list); } catch { /* ignore */ }
    }
  }

  await pushData();
  // 再推一次，防止首次监听尚未挂上
  setTimeout(() => { pushData(); }, 120);

  try {
    await win.setAlwaysOnTop(true);
    await win.show();
    // 轻微改尺寸触发 WebView2 重绘（修只见边框）
    try {
      await win.setSize(new LogicalSize(width, height + 1));
      await win.setSize(new LogicalSize(width, height));
    } catch { /* ignore */ }
    await win.setFocus();
  } catch (e) {
    console.warn(`[${label}] show failed`, e);
  }

  return true;
}
