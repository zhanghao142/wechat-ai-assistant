/**
 * Tauri 系统级提醒小窗（屏幕右下角、置顶）。
 */

import { isTauri, syncPopupWindow } from './popupWindow';

export { isTauri };

const WIDTH = 380;
const BASE_HEIGHT = 180;
const STACK_OFFSET = 16;

function sessionsTitle(sessionId) {
  try {
    const list = JSON.parse(localStorage.getItem('sessions') || '[]');
    return list.find(s => s.id === sessionId)?.title || '对话';
  } catch {
    return '对话';
  }
}

/**
 * @param {Array<{notificationId?:string, sessionId:string, content:string}>} items
 */
export async function syncSystemRemindWindow(items) {
  const payload = (items || []).map(n => ({
    notificationId: n.notificationId || null,
    sessionId: n.sessionId,
    content: n.content || '提醒',
    title: sessionsTitle(n.sessionId)
  }));
  const height = Math.min(480, BASE_HEIGHT + Math.max(0, payload.length - 1) * 110);
  return syncPopupWindow({
    label: 'remind-popup',
    url: 'remind.html',
    title: '提醒',
    storageKey: 'remindPopupPayload',
    dataEvent: 'remind-popup-data',
    readyEvent: 'remind-popup-ready',
    width: WIDTH,
    height,
    yOffset: STACK_OFFSET,
    payload
  });
}
