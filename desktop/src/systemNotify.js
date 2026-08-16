/**
 * Windows / 系统级 Toast 通知（Tauri notification 插件）。
 * 浏览器预览下无操作；仅 Tauri 壳可用。
 */

import { isTauri } from './popupWindow';

let permissionReady = null;

async function ensurePermission() {
  if (!isTauri()) return false;
  if (permissionReady != null) return permissionReady;
  try {
    const {
      isPermissionGranted,
      requestPermission,
    } = await import('@tauri-apps/plugin-notification');
    let granted = await isPermissionGranted();
    if (!granted) {
      granted = (await requestPermission()) === 'granted';
    }
    permissionReady = granted;
    return granted;
  } catch (e) {
    console.warn('[notify] permission failed', e);
    permissionReady = false;
    return false;
  }
}

/**
 * 发送一条系统通知（失败静默，不影响现有提醒小窗）。
 * @param {{ title?: string, body?: string }} opts
 */
export async function notifySystem({ title = '小微', body = '' } = {}) {
  if (!isTauri()) return;
  try {
    const ok = await ensurePermission();
    if (!ok) return;
    const { sendNotification } = await import('@tauri-apps/plugin-notification');
    sendNotification({
      title: String(title || '小微').slice(0, 64),
      body: String(body || '你有一条新提醒').slice(0, 200),
    });
  } catch (e) {
    console.warn('[notify] send failed', e);
  }
}
