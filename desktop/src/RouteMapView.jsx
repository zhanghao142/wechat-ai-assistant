import { useEffect, useRef, useState } from 'react';
import { loadAmap, getAmapKey } from './amapLoader';
import { getLastRoute } from './routeStore';
import './RouteMapView.css';

/**
 * 平面路线图：默认全球宏观视野；有结构化路线时画线并 fitView。
 */
export default function RouteMapView({ onBack }) {
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const overlaysRef = useRef([]);
  const [status, setStatus] = useState('加载地图…');
  const [routeInfo, setRouteInfo] = useState(() => getLastRoute());
  const [error, setError] = useState('');

  useEffect(() => {
    const onUpd = (e) => setRouteInfo(e.detail || getLastRoute());
    window.addEventListener('route-updated', onUpd);
    return () => window.removeEventListener('route-updated', onUpd);
  }, []);

  useEffect(() => {
    let cancelled = false;
    let map;

    (async () => {
      try {
        if (!getAmapKey()) {
          setError('未配置高德 Key：在 desktop/.env 写入 VITE_AMAP_KEY=你的Web端JS Key 后重启桌面前端');
          setStatus('等待配置');
          return;
        }
        const AMap = await loadAmap();
        if (cancelled || !containerRef.current) return;

        map = new AMap.Map(containerRef.current, {
          viewMode: '2D',
          zoom: 3,
          center: [20, 20],
          mapStyle: 'amap://styles/whitesmoke',
        });
        map.addControl(new AMap.Scale());
        map.addControl(new AMap.ToolBar({ position: 'RB' }));
        mapRef.current = map;
        setStatus('全球宏观视图');
        setError('');

        await renderRoute(AMap, map, getLastRoute());
      } catch (e) {
        if (!cancelled) {
          setError(e?.message || String(e));
          setStatus('加载失败');
        }
      }
    })();

    return () => {
      cancelled = true;
      clearOverlays();
      if (mapRef.current) {
        mapRef.current.destroy();
        mapRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !window.AMap) return;
    renderRoute(window.AMap, map, routeInfo).catch((e) => {
      setError(e?.message || String(e));
    });
  }, [routeInfo]);

  function clearOverlays() {
    const map = mapRef.current;
    for (const o of overlaysRef.current) {
      try {
        map?.remove(o);
      } catch { /* ignore */ }
    }
    overlaysRef.current = [];
  }

  async function renderRoute(AMap, map, route) {
    clearOverlays();
    if (!route) {
      map.setZoomAndCenter(3, [20, 20]);
      setStatus('全球宏观视图 · 暂无规划路线');
      return;
    }

    let path = Array.isArray(route.path) ? route.path.filter(isLngLat) : [];

    // 仅有起终点坐标：用驾车规划补折线
    if (path.length < 2 && isLngLat(route.originLngLat) && isLngLat(route.destLngLat)) {
      setStatus('正在根据坐标补全路线…');
      try {
        path = await planDrivingPathByLngLat(AMap, route.originLngLat, route.destLngLat);
      } catch (e) {
        path = [route.originLngLat, route.destLngLat];
      }
    }

    // 仅有起终点名称：用关键词规划
    if (path.length < 2 && route.origin && route.destination
        && !isCoordLabel(route.origin) && !isCoordLabel(route.destination)) {
      setStatus('正在根据起终点规划折线…');
      path = await planDrivingPath(AMap, route.origin, route.destination);
    }

    if (path.length < 2 && isLngLat(route.originLngLat) && isLngLat(route.destLngLat)) {
      path = [route.originLngLat, route.destLngLat];
    }

    if (path.length < 2) {
      map.setZoomAndCenter(3, [20, 20]);
      setStatus('全球宏观视图 · 路线数据不足');
      setError('未拿到折线坐标。请重新发一次路线规划后再打开本页。');
      return;
    }

    setError('');
    const polyline = new AMap.Polyline({
      path,
      strokeColor: '#2563eb',
      strokeWeight: 6,
      strokeOpacity: 0.9,
      lineJoin: 'round',
      lineCap: 'round',
      showDir: true,
    });
    const start = new AMap.Marker({
      position: path[0],
      title: route.origin || '起点',
      label: { content: '起', direction: 'top' },
    });
    const end = new AMap.Marker({
      position: path[path.length - 1],
      title: route.destination || '终点',
      label: { content: '终', direction: 'top' },
    });
    map.add([polyline, start, end]);
    overlaysRef.current = [polyline, start, end];
    map.setFitView([polyline], false, [60, 60, 60, 60]);

    const bits = [];
    if (route.origin && route.destination) bits.push(`${route.origin} → ${route.destination}`);
    if (route.distanceMeters) bits.push(`${(Number(route.distanceMeters) / 1000).toFixed(1)} km`);
    if (route.mode) bits.push(route.mode);
    setStatus(bits.length ? bits.join(' · ') : '已渲染规划路线');
  }

  return (
    <div className="route-map-page">
      <header className="route-map-topbar">
        <button type="button" className="route-map-back" onClick={onBack}>
          ← 返回对话
        </button>
        <div className="route-map-title-block">
          <h1 className="route-map-title">路线地图</h1>
          <p className="route-map-sub">{status}</p>
        </div>
        <div className="route-map-spacer" />
      </header>
      {error ? <div className="route-map-error">{error}</div> : null}
      <div className="route-map-canvas" ref={containerRef} />
    </div>
  );
}

function isLngLat(p) {
  return Array.isArray(p) && p.length >= 2
    && Number.isFinite(Number(p[0])) && Number.isFinite(Number(p[1]));
}

function isCoordLabel(s) {
  return /^\s*-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?\s*$/.test(String(s || ''));
}

function planDrivingPath(AMap, origin, destination) {
  return new Promise((resolve, reject) => {
    const driving = new AMap.Driving({ policy: AMap.DrivingPolicy.LEAST_TIME });
    driving.search(
      [{ keyword: String(origin) }, { keyword: String(destination) }],
      (status, result) => {
        if (status !== 'complete' || !result?.routes?.[0]) {
          reject(new Error(result?.info || '驾车路径规划失败'));
          return;
        }
        resolve(flattenDrivingPath(result.routes[0]));
      },
    );
  });
}

function planDrivingPathByLngLat(AMap, originLL, destLL) {
  return new Promise((resolve, reject) => {
    const driving = new AMap.Driving({ policy: AMap.DrivingPolicy.LEAST_TIME });
    const o = new AMap.LngLat(Number(originLL[0]), Number(originLL[1]));
    const d = new AMap.LngLat(Number(destLL[0]), Number(destLL[1]));
    driving.search(o, d, (status, result) => {
      if (status !== 'complete' || !result?.routes?.[0]) {
        reject(new Error(result?.info || '驾车路径规划失败'));
        return;
      }
      resolve(flattenDrivingPath(result.routes[0]));
    });
  });
}

function flattenDrivingPath(route) {
  const steps = route.steps || [];
  const path = [];
  for (const step of steps) {
    const sp = step.path || [];
    for (const pt of sp) {
      path.push([pt.lng, pt.lat]);
    }
  }
  if (path.length < 2) throw new Error('规划结果无坐标点');
  return path;
}
