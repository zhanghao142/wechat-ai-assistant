/**
 * Tauri 系统级确认小窗（屏幕右下角、置顶）。
 */

import { isTauri, syncPopupWindow } from './popupWindow';

export { isTauri };

const WIDTH = 380;
const BASE_HEIGHT = 200;

function sessionsTitle(sessionId) {
  try {
    const list = JSON.parse(localStorage.getItem('sessions') || '[]');
    return list.find(s => s.id === sessionId)?.title || '对话';
  } catch {
    return '对话';
  }
}

/** @param {Array<{sessionId:string, description:string, confirmThreadId?:string|null}>} cards */
export async function syncSystemConfirmWindow(cards) {
  const payload = (cards || []).map(c => ({
    sessionId: c.sessionId,
    description: c.description || '需要确认一项操作',
    confirmThreadId: c.confirmThreadId || null,
    title: sessionsTitle(c.sessionId)
  }));
  const height = Math.min(420, BASE_HEIGHT + Math.max(0, payload.length - 1) * 120);
  return syncPopupWindow({
    label: 'confirm-popup',
    url: 'confirm.html',
    title: '需要确认',
    storageKey: 'confirmPopupPayload',
    dataEvent: 'confirm-popup-data',
    readyEvent: 'confirm-popup-ready',
    width: WIDTH,
    height,
    yOffset: 0,
    payload
  });
}
