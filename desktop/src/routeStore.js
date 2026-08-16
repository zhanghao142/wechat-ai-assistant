/**
 * 最近一次结构化路线（硬编码管道写入，不经 LLM 判断）。
 * @typedef {{
 *   origin?: string,
 *   destination?: string,
 *   originLngLat?: [number, number],
 *   destLngLat?: [number, number],
 *   path?: [number, number][],
 *   distanceMeters?: number,
 *   durationSeconds?: number,
 *   mode?: string,
 *   updatedAt?: number,
 * }} RoutePayload
 */

const KEY = 'desktop.lastRoute';

/** @returns {RoutePayload | null} */
export function getLastRoute() {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const o = JSON.parse(raw);
    if (!o || typeof o !== 'object') return null;
    return o;
  } catch {
    return null;
  }
}

/** @param {RoutePayload | null | undefined} route */
export function setLastRoute(route) {
  if (!route || typeof route !== 'object') return;
  const payload = {
    ...route,
    updatedAt: Date.now(),
  };
  try {
    localStorage.setItem(KEY, JSON.stringify(payload));
  } catch {
    /* ignore quota */
  }
  window.dispatchEvent(new CustomEvent('route-updated', { detail: payload }));
}

export function clearLastRoute() {
  localStorage.removeItem(KEY);
  window.dispatchEvent(new CustomEvent('route-updated', { detail: null }));
}

/** 从 SSE attachments 里取出 type=route */
export function pickRouteFromAttachments(attachments) {
  if (!Array.isArray(attachments)) return null;
  for (const a of attachments) {
    if (a && a.type === 'route') return a;
  }
  return null;
}
