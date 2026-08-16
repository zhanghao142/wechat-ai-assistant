/**
 * 加载高德 JS API（Key 填在 desktop/.env → VITE_AMAP_KEY）
 */

const SCRIPT_ID = 'amap-jsapi-script';

export function getAmapKey() {
  const fromEnv = String(import.meta.env.VITE_AMAP_KEY || '').trim();
  if (fromEnv) return fromEnv;
  try {
    return String(localStorage.getItem('amap.js.key') || '').trim();
  } catch {
    return '';
  }
}

/**
 * @returns {Promise<typeof window.AMap>}
 */
export function loadAmap() {
  const key = getAmapKey();
  if (!key) {
    return Promise.reject(new Error(
      '未配置高德 Key。请在 desktop/.env 设置 VITE_AMAP_KEY=你的Key，或在本机 localStorage 写入 amap.js.key',
    ));
  }

  if (window.AMap) return Promise.resolve(window.AMap);

  const existing = document.getElementById(SCRIPT_ID);
  if (existing) {
    return new Promise((resolve, reject) => {
      const t0 = Date.now();
      const timer = setInterval(() => {
        if (window.AMap) {
          clearInterval(timer);
          resolve(window.AMap);
        } else if (Date.now() - t0 > 20000) {
          clearInterval(timer);
          reject(new Error('高德地图脚本加载超时'));
        }
      }, 50);
    });
  }

  return new Promise((resolve, reject) => {
    // 安全密钥可选：VITE_AMAP_SECURITY_CODE
    const security = String(import.meta.env.VITE_AMAP_SECURITY_CODE || '').trim();
    if (security) {
      window._AMapSecurityConfig = { securityJsCode: security };
    }

    const script = document.createElement('script');
    script.id = SCRIPT_ID;
    script.async = true;
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&plugin=AMap.Driving,AMap.Scale,AMap.ToolBar`;
    script.onload = () => {
      if (window.AMap) resolve(window.AMap);
      else reject(new Error('AMap 未挂载'));
    };
    script.onerror = () => reject(new Error('高德地图脚本加载失败，请检查 Key / 网络'));
    document.head.appendChild(script);
  });
}
