#!/usr/bin/env node
/**
 * 通用联网搜索脚本（无第三方依赖）— 多源合并
 * 用法: node web-search.js [--limit N] "搜索关键词"
 * stdout: 供 Java searchInternet 直接返回的纯文本
 * stderr: 调试日志
 * exit 0 成功；1 失败/无结果
 */
'use strict';

const https = require('https');
const http = require('http');
const { URL } = require('url');

function parseArgs(argv) {
  let limit = 8;
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--limit' && argv[i + 1]) {
      limit = Math.max(1, Math.min(12, parseInt(argv[++i], 10) || 8));
    } else {
      rest.push(argv[i]);
    }
  }
  return { limit, query: rest.join(' ').trim() };
}

function fetchText(url, timeoutMs = 15000, extraHeaders = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const lib = u.protocol === 'https:' ? https : http;
    const req = lib.get(
      url,
      {
        headers: {
          'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
          'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
          Accept:
            'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
          'Cache-Control': 'no-cache',
          'Upgrade-Insecure-Requests': '1',
          ...extraHeaders,
        },
      },
      (res) => {
        if (
          res.statusCode >= 300 &&
          res.statusCode < 400 &&
          res.headers.location
        ) {
          const next = new URL(res.headers.location, url).toString();
          res.resume();
          return resolve(fetchText(next, timeoutMs, extraHeaders));
        }
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          resolve(Buffer.concat(chunks).toString('utf8'));
        });
      }
    );
    req.setTimeout(timeoutMs, () => {
      req.destroy(new Error('timeout'));
    });
    req.on('error', reject);
  });
}

function cleanText(s) {
  return String(s || '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<[^>]+>/g, '')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/\u00a0/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function normalizeUrl(raw, baseHost) {
  if (!raw) return '';
  let u = String(raw).trim().replace(/&amp;/g, '&');
  if (u.startsWith('//')) u = 'https:' + u;
  if (u.startsWith('/') && baseHost) {
    u = `https://${baseHost}${u}`;
  }
  // DuckDuckGo / 搜狗跳转壳
  try {
    const parsed = new URL(u.startsWith('http') ? u : 'https://' + u);
    if (parsed.hostname.includes('duckduckgo.com') && parsed.searchParams.get('uddg')) {
      return decodeURIComponent(parsed.searchParams.get('uddg'));
    }
    if (parsed.hostname.includes('sogou.com')) {
      const link =
        parsed.searchParams.get('url') ||
        parsed.searchParams.get('href') ||
        (parsed.pathname === '/link' ? parsed.searchParams.get('url') : null);
      if (link) {
        try {
          return decodeURIComponent(link);
        } catch {
          return link;
        }
      }
    }
  } catch (_) {
    /* ignore */
  }
  return u;
}

function isJunk(title, url) {
  const u = (url || '').toLowerCase();
  const t = title || '';
  if (!u || u.startsWith('javascript:') || u.startsWith('/link?')) return true;
  if (u.includes('nourl.ubs.baidu.com')) return true;
  if (u.includes('recommend_list.baidu.com')) return true;
  if (u.includes('top.baidu.com/board')) return true;
  if (u.includes('fakeurl.baidu.com')) return true;
  if (u.includes('big5.www.gov.cn') || u.includes('/gate/big5/')) return true;
  if (t.includes('中國政府網') || t.includes('中央人民政府門戶')) return true;
  if (u.includes('cma-cgm.com')) return true;
  if (u.includes('travelchina.gov.cn')) return true;
  if (t.includes('中华人民共和国_百度百科')) return true;
  if (t.startsWith('第（汉语汉字）_百度百科')) return true;
  // 中文检索时丢掉明显日文门户噪音
  if (u.includes('yahoo.co.jp') || u.includes('news.yahoo.co.jp')) return true;

  try {
    const parsed = new URL(u.startsWith('http') ? u : 'https://' + u);
    const host = (parsed.hostname || '').toLowerCase();
    const path = parsed.pathname || '/';
    const bare =
      path === '/' ||
      path === '' ||
      path === '/index.htm' ||
      path === '/index.html';
    if (host.includes('gov.cn') && (bare || path.includes('/gate/'))) return true;
    if (
      bare &&
      [
        'www.gov.cn',
        'gov.cn',
        'www.china.com.cn',
        'www.china.com',
        'www.people.com.cn',
        'www.stats.gov.cn',
        'www.chnmuseum.cn',
        'www.cma.gov.cn',
        'www.ncc-cma.net',
        'ncc-cma.net',
      ].includes(host)
    ) {
      return true;
    }
  } catch (_) {
    /* ignore */
  }
  return false;
}

function pushHit(hits, seen, title, url, snippet, source, baseHost) {
  const u = normalizeUrl(url, baseHost);
  const t = cleanText(title);
  if (!u || !/^https?:\/\//i.test(u) || t.length < 4 || seen.has(u) || isJunk(t, u)) {
    return;
  }
  seen.add(u);
  hits.push({ title: t, url: u, snippet: cleanText(snippet || ''), source });
}

function parseBaidu(html) {
  const hits = [];
  const seen = new Set();
  const re =
    /mu="(https?:\/\/[^"]+)"[\s\S]{0,3000}?<h3[\s\S]*?<a[\s\S]*?>([\s\S]*?)<\/a>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    pushHit(hits, seen, m[2], m[1], '', 'baidu');
  }
  return hits;
}

function parseBing(html) {
  const hits = [];
  const seen = new Set();
  const blocks = html.match(/class="b_algo"[\s\S]*?<\/li>/gi) || [];
  for (const block of blocks) {
    const m = block.match(
      /<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/i
    );
    if (!m) continue;
    const sn = block.match(
      /class="b_caption"[^>]*>[\s\S]*?<p[^>]*>([\s\S]*?)<\/p>/i
    );
    pushHit(hits, seen, m[2], m[1], sn ? sn[1] : '', 'bing');
  }
  return hits;
}

/** DuckDuckGo HTML 版（无需 API Key） */
function parseDuckDuckGo(html) {
  const hits = [];
  const seen = new Set();
  const blocks = html.match(/class="result[\s\S]*?<\/article>/gi)
    || html.match(/class="result__body"[\s\S]*?(?=class="result__body"|$)/gi)
    || [];
  const reA =
    /class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)<\/a>/gi;
  let m;
  const html2 = blocks.length ? blocks.join('\n') : html;
  while ((m = reA.exec(html2)) !== null) {
    const snM = html2
      .slice(m.index, m.index + 800)
      .match(/class="result__snippet"[^>]*>([\s\S]*?)<\/(?:a|td|div)/i);
    pushHit(hits, seen, m[2], m[1], snM ? snM[1] : '', 'duckduckgo');
  }
  return hits;
}

/** 搜狗 */
function parseSogou(html) {
  const hits = [];
  const seen = new Set();
  const re =
    /<h3[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)<\/a>\s*<\/h3>/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    const snM = html
      .slice(m.index, m.index + 1200)
      .match(/class="[^"]*str-info[^"]*"[^>]*>([\s\S]*?)<\/p>/i)
      || html
        .slice(m.index, m.index + 1200)
        .match(/class="star-wiki"|class="[^"]*space-txt[^"]*"[^>]*>([\s\S]*?)<\//i);
    pushHit(hits, seen, m[2], m[1], snM ? snM[1] : '', 'sogou', 'www.sogou.com');
  }
  return hits;
}

function preferRelevant(hits, query) {
  const tokens = [];
  const cn = query.match(/[\u4e00-\u9fff]{2,}/g) || [];
  const en = query.match(/[A-Za-z]{3,}/g) || [];
  for (const t of [...cn, ...en]) {
    if (['相关', '数据', '中国', '网站', '研究', 'site'].includes(t)) continue;
    tokens.push(t.toLowerCase());
  }
  if (!tokens.length) return hits;
  const relevant = hits.filter((h) => {
    const blob = `${h.title} ${h.snippet} ${h.url}`.toLowerCase();
    return tokens.some((t) => blob.includes(t));
  });
  return relevant.length ? relevant : hits;
}

/** 多源交错，避免结果全被单一引擎占满 */
function interleaveBySource(hits, limit) {
  const by = new Map();
  for (const h of hits) {
    const s = h.source || 'other';
    if (!by.has(s)) by.set(s, []);
    by.get(s).push(h);
  }
  const keys = [...by.keys()];
  const out = [];
  const seen = new Set();
  let i = 0;
  while (out.length < limit) {
    let added = false;
    for (const k of keys) {
      const arr = by.get(k);
      if (i < arr.length) {
        const h = arr[i];
        if (!seen.has(h.url)) {
          seen.add(h.url);
          out.push(h);
          added = true;
          if (out.length >= limit) break;
        }
      }
    }
    if (!added) break;
    i += 1;
  }
  // 补齐
  if (out.length < limit) {
    for (const h of hits) {
      if (out.length >= limit) break;
      if (!seen.has(h.url)) {
        seen.add(h.url);
        out.push(h);
      }
    }
  }
  return out;
}

function formatOutput(query, hits, limit, sourceStats) {
  const lines = [
    `搜索结果: ${query}`,
    `来源合并: ${sourceStats}`,
    '',
    '【重要】下面「链接」均为真实 URL。后续 readUrl / navigate 只能使用这些链接，禁止编造或拼接网址。',
    '【建议】已有 3 条以上可用链接时，优先 readUrl 精读，不要同义反复 searchInternet。',
    '',
  ];
  for (const h of hits.slice(0, limit)) {
    lines.push(`标题: ${h.title}`);
    lines.push(`链接: ${h.url}`);
    lines.push(`摘要: ${h.snippet || ''}`);
    if (h.source) lines.push(`引擎: ${h.source}`);
    lines.push('');
  }
  return lines.join('\n').trim() + '\n';
}

async function runSource(name, fn) {
  try {
    const list = await fn();
    process.stderr.write(`[web-search] ${name} hits=${list.length}\n`);
    return list;
  } catch (e) {
    process.stderr.write(`[web-search] ${name} failed: ${e.message}\n`);
    return [];
  }
}

async function main() {
  const { limit, query } = parseArgs(process.argv.slice(2));
  if (!query) {
    process.stderr.write('usage: node web-search.js [--limit N] "query"\n');
    process.exit(1);
  }

  const q = encodeURIComponent(query);

  const batches = await Promise.all([
    runSource('baidu', async () => {
      const html = await fetchText(
        `https://www.baidu.com/s?ie=utf-8&wd=${q}&rn=10`,
        15000,
        { Referer: 'https://www.baidu.com/' }
      );
      return parseBaidu(html);
    }),
    runSource('bing', async () => {
      const html = await fetchText(
        `https://cn.bing.com/search?q=${q}&count=10`,
        15000,
        { Referer: 'https://cn.bing.com/' }
      );
      return parseBing(html);
    }),
    runSource('duckduckgo', async () => {
      const html = await fetchText(
        `https://html.duckduckgo.com/html/?q=${q}`,
        15000,
        { Referer: 'https://html.duckduckgo.com/' }
      );
      return parseDuckDuckGo(html);
    }),
    runSource('sogou', async () => {
      const html = await fetchText(
        `https://www.sogou.com/web?query=${q}`,
        15000,
        { Referer: 'https://www.sogou.com/' }
      );
      return parseSogou(html);
    }),
  ]);

  const merged = new Map();
  const sourceCounts = {};
  for (const list of batches) {
    for (const h of list) {
      if (!merged.has(h.url)) {
        merged.set(h.url, h);
        sourceCounts[h.source] = (sourceCounts[h.source] || 0) + 1;
      }
    }
  }

  let hits = preferRelevant([...merged.values()], query);
  hits = interleaveBySource(hits, limit);

  const sourceStats = Object.keys(sourceCounts).length
    ? Object.entries(sourceCounts)
        .map(([k, v]) => `${k}:${v}`)
        .join(', ')
    : '无';

  if (!hits.length) {
    process.stdout.write(`未找到相关结果，请尝试更换搜索关键词: ${query}\n`);
    process.exit(1);
  }

  process.stderr.write(
    `[web-search] merged=${merged.size} out=${hits.length} (${sourceStats})\n`
  );
  process.stdout.write(formatOutput(query, hits, limit, sourceStats));
  process.exit(0);
}

main().catch((e) => {
  process.stderr.write(`[web-search] fatal: ${e.message}\n`);
  process.exit(1);
});
