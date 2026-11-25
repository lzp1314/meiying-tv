'use strict';

/* IPTV 空壳播放器 · Web 原型服务端
 * 零第三方依赖。默认只监听 127.0.0.1；跨设备配对需显式 IPTV_BIND=0.0.0.0。
 */
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const IPTV = require('./lib/parse.js');
const DEMO = require('./lib/demo.js');

const ROOT = __dirname;
const DATA_DIR = path.join(ROOT, 'data');
const DATA_FILE = path.join(DATA_DIR, 'sources.json');
const STATIC = {
  '/': ['index.html', 'text/html; charset=utf-8'],
  '/index.html': ['index.html', 'text/html; charset=utf-8'],
  '/app.css': ['app.css', 'text/css; charset=utf-8'],
  '/app.js': ['app.js', 'application/javascript; charset=utf-8'],
  '/lib/parse.js': ['lib/parse.js', 'application/javascript; charset=utf-8'],
  '/lib/demo.js': ['lib/demo.js', 'application/javascript; charset=utf-8'],
};
const MAX_BODY = 1024 * 1024;
const FETCH_TIMEOUT = 12000;
const MAX_FETCH_BYTES = 6 * 1024 * 1024;

let PORT = Number(process.env.IPTV_PORT) || 8790;
const BIND = process.env.IPTV_BIND || '127.0.0.1';
const LAN_IP = (() => {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const n of list || []) {
      if (n.family === 'IPv4' && !n.internal) return n.address;
    }
  }
  return '127.0.0.1';
})();

let state;                       // { sources: [] }
const cache = new Map();          // sourceId -> 最近一次解析结果
const sseClients = new Set();
const pairCodes = new Map();      // code -> { createdAt, used }

// ---------------------------------------------------------------- 持久化

function ensureData() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
  if (!fs.existsSync(DATA_FILE)) {
    fs.writeFileSync(DATA_FILE, JSON.stringify({ sources: [] }, null, 2) + '\n', 'utf8');
  }
}
function loadState() {
  const s = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
  if (!Array.isArray(s.sources)) throw new Error('sources 字段必须是数组');
  return s;
}
function saveState() {
  const tmp = DATA_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(state, null, 2) + '\n', 'utf8');
  fs.renameSync(tmp, DATA_FILE);
}
function newId() { return 'src-' + crypto.randomBytes(5).toString('hex'); }

// ---------------------------------------------------------------- 工具

function sendJson(res, code, data) {
  const body = JSON.stringify(data);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}
function sendText(res, code, text, type) {
  const body = Buffer.from(String(text));
  res.writeHead(code, { 'Content-Type': type || 'text/plain; charset=utf-8', 'Content-Length': body.length, 'Cache-Control': 'no-store' });
  res.end(body);
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(new Error('请求体过大')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      if (!raw) return resolve({});
      try { resolve(JSON.parse(raw)); } catch { reject(new Error('JSON 格式错误')); }
    });
    req.on('error', reject);
  });
}
function originAllowed(req) {
  const o = req.headers.origin;
  if (!o) return true;
  try {
    const u = new URL(o);
    return u.protocol === 'http:' && u.port === String(PORT) &&
      ['127.0.0.1', 'localhost', LAN_IP].indexOf(u.hostname) !== -1;
  } catch { return false; }
}
function serveStatic(res, p) {
  const entry = STATIC[p];
  if (!entry) return sendJson(res, 404, { error: 'Not Found' });
  const file = path.join(ROOT, entry[0]);
  if (!fs.existsSync(file)) return sendJson(res, 500, { error: '缺少文件 ' + entry[0] });
  const body = fs.readFileSync(file);
  res.writeHead(200, { 'Content-Type': entry[1], 'Content-Length': body.length, 'Cache-Control': 'no-store' });
  res.end(body);
}
function broadcast(payload) {
  const line = 'data: ' + JSON.stringify(payload) + '\n\n';
  for (const res of sseClients) {
    try { res.write(line); } catch { sseClients.delete(res); }
  }
}

/* 代理拉取：协议白名单 + 超时 + 大小上限。
 * 注意：hd-iptv 部署在内网，因此不能禁止私网地址；这是本地工具的既定边界。 */
async function fetchText(rawUrl) {
  const n = IPTV.normalizeSourceUrl(rawUrl);
  if (!n.ok) throw new Error(n.error);
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT);
  try {
    const res = await fetch(n.url, {
      signal: ctrl.signal, redirect: 'follow',
      headers: { 'User-Agent': 'IPTVPlayer-Prototype/0.1', 'Accept': '*/*' },
    });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const reader = res.body.getReader();
    const chunks = []; let size = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (size > MAX_FETCH_BYTES) { await reader.cancel(); throw new Error('响应超过 6 MB'); }
      chunks.push(Buffer.from(value));
    }
    return Buffer.concat(chunks).toString('utf8');
  } catch (e) {
    if (e.name === 'AbortError') throw new Error('请求超时（12 秒）');
    throw e;
  } finally { clearTimeout(timer); }
}

// ---------------------------------------------------------------- 数据层调用

async function refreshSource(src) {
  const m3uText = await fetchText(src.url);
  const parsed = IPTV.parseM3u(m3uText);
  if (!parsed.channels.length) throw new Error('订阅内容里没有解析到频道');
  const epgUrl = src.epgUrl || IPTV.resolveEpgUrl(src.url, parsed.epgUrl);
  let epg = null, epgError = '';
  if (epgUrl) {
    try { epg = IPTV.parseXmltv(await fetchText(epgUrl)); }
    catch (e) { epgError = String(e.message || e); }
  }
  const now = Date.now();
  const channels = parsed.channels.map((ch) => {
    const nn = epg ? IPTV.nowNext(epg, ch.tvgId, ch.name, now) : { current: null, next: null };
    return {
      ...ch,
      current: nn.current ? nn.current.title : '',
      currentStart: nn.current ? nn.current.start : null,
      currentStop: nn.current ? nn.current.stop : null,
      next: nn.next ? nn.next.title : '',
    };
  });
  const result = {
    sourceId: src.id, name: src.name, subscriptionUrl: src.url,
    declaredEpgUrl: parsed.epgUrl, epgUrl, epgError,
    epgChannelCount: epg ? Object.keys(epg.programmes).length : 0,
    epgMatchedCount: channels.filter((c) => c.current || c.next).length,
    channelCount: channels.length,
    groups: IPTV.groupChannels(channels), channels,
    warnings: parsed.warnings, fetchedAt: now,
  };
  cache.set(src.id, result);
  const idx = state.sources.findIndex((s) => s.id === src.id);
  if (idx !== -1) {
    state.sources[idx].lastOk = now;
    state.sources[idx].channelCount = channels.length;
    state.sources[idx].epgUrl = epgUrl;
    saveState();
  }
  return result;
}

// ---------------------------------------------------------------- 配对

function pairHost() { return BIND === '0.0.0.0' ? LAN_IP : '127.0.0.1'; }

function pairPage(code) {
  return `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>推送订阅源</title>
<style>body{font:15px/1.7 -apple-system,"Microsoft YaHei",sans-serif;background:#f4f6fa;color:#1e293b;margin:0;padding:24px}
.wrap{max-width:480px;margin:0 auto;background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:22px}
h1{font-size:17px;margin:0 0 6px}p{font-size:12px;color:#64748b;margin:0 0 18px}
label{display:block;font-size:12px;font-weight:600;margin:12px 0 5px}
input{width:100%;box-sizing:border-box;padding:10px;border:1px solid #cbd5e1;border-radius:7px;font-size:14px}
button{width:100%;margin-top:18px;padding:11px;border:0;border-radius:7px;background:#2563eb;color:#fff;font-size:14px}
.msg{margin-top:14px;font-size:13px;min-height:20px}.ok{color:#15803d}.bad{color:#b91c1c}
code{background:#f1f5f9;padding:2px 6px;border-radius:4px}</style></head><body>
<div class="wrap"><h1>把订阅地址推送到播放器</h1>
<p>配对码 <code>${code}</code> · 填写你在 hd-iptv 面板复制的 M3U 订阅地址</p>
<form id="f"><label>订阅地址</label>
<input id="url" type="url" required placeholder="http://NAS:9797/密码/playlist.m3u">
<label>名称（可选）</label><input id="name" type="text" placeholder="家里 NAS">
<button type="submit">推送</button><div class="msg" id="msg"></div></form></div>
<script>
document.getElementById("f").addEventListener("submit", async (e) => {
  e.preventDefault();
  const msg = document.getElementById("msg");
  msg.textContent = "正在推送…"; msg.className = "msg";
  try {
    const r = await fetch("/api/pair/submit", {method:"POST", headers:{"Content-Type":"application/json"},
      body: JSON.stringify({code:"${code}", url:document.getElementById("url").value.trim(), name:document.getElementById("name").value.trim()})});
    const d = await r.json();
    if (!r.ok) throw new Error(d.error || "HTTP " + r.status);
    msg.className = "msg ok"; msg.textContent = "已推送，回到播放器查看源列表。";
  } catch (err) { msg.className = "msg bad"; msg.textContent = "失败：" + err.message; }
});
</script></body></html>`;
}

// ---------------------------------------------------------------- 路由

async function handle(req, res) {
  const u = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const p = u.pathname;
  const method = req.method.toUpperCase();
  if (!originAllowed(req)) return sendJson(res, 403, { error: '跨源请求被拒绝' });

  if (method === 'GET' && STATIC[p]) return serveStatic(res, p);
  if (method === 'GET' && p === '/api/health') {
    return sendJson(res, 200, { ok: true, port: PORT, bind: BIND, lanIp: LAN_IP, dataFile: DATA_FILE });
  }
  if (method === 'GET' && p === '/api/state') return sendJson(res, 200, state);
  if (method === 'GET' && p === '/api/data-rules') {
    return sendJson(res, 200, {
      m3u: ['#EXTM3U 的 x-tvg-url / url-tvg', '#EXTINF 属性与逗号后的显示名', '#EXTVLCOPT 的 UA / Referer'],
      epg: ['优先 M3U 声明', '否则订阅 URL 同源推导 /epg.xml 并保留访问密码路径段'],
      xmltv: ['channel id / display-name', 'programme start / stop / channel / title', 'tvg-id 优先，display-name 反查兜底'],
    });
  }
  if (method === 'GET' && p === '/api/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache, no-transform', Connection: 'keep-alive' });
    res.write(': connected\n\n');
    sseClients.add(res);
    const hb = setInterval(() => { try { res.write(': ping\n\n'); } catch { /* ignore */ } }, 20000);
    req.on('close', () => { clearInterval(hb); sseClients.delete(res); });
    return;
  }

  // 演示源（合成数据，不含真实频道与可播流）
  if (method === 'GET' && p === '/demo/playlist.m3u') {
    return sendText(res, 200, DEMO.demoM3u('http://127.0.0.1:' + PORT), 'application/vnd.apple.mpegurl; charset=utf-8');
  }
  if (method === 'GET' && p === '/demo/epg.xml') return sendText(res, 200, DEMO.demoXmltv(), 'application/xml; charset=utf-8');
  if (method === 'GET' && p.startsWith('/demo/stream/')) {
    return sendText(res, 404, '# 演示流：此处刻意不提供可播放内容，用于验证错误提示\n', 'application/vnd.apple.mpegurl; charset=utf-8');
  }
  if (method === 'POST' && p === '/api/demo/add') {
    const src = {
      id: 'demo-source', name: '[演示] 合成数据源', url: 'http://127.0.0.1:' + PORT + '/demo/playlist.m3u',
      epgUrl: '', demo: true, addedAt: Date.now(),
    };
    const idx = state.sources.findIndex((s) => s.id === src.id);
    if (idx === -1) state.sources.push(src); else state.sources[idx] = src;
    saveState();
    return sendJson(res, 200, src);
  }

  // 源列表 CRUD
  if (method === 'PUT' && p.startsWith('/api/sources/')) {
    const id = p.slice('/api/sources/'.length);
    if (!id) return sendJson(res, 400, { error: '缺少 ID' });
    let body;
    try { body = await readBody(req); } catch (e) { return sendJson(res, 400, { error: e.message }); }
    const n = IPTV.normalizeSourceUrl(body.url);
    if (!n.ok) return sendJson(res, 400, { error: n.error });
    if (body.epgUrl && body.epgUrl.trim()) {
      const en = IPTV.normalizeSourceUrl(body.epgUrl.trim());
      if (!en.ok) return sendJson(res, 400, { error: 'EPG 地址：' + en.error });
    }
    const item = {
      id, name: String(body.name || '').trim() || '未命名源', url: n.url,
      epgUrl: String(body.epgUrl || '').trim(), demo: !!body.demo,
      addedAt: Date.now(),
    };
    const idx = state.sources.findIndex((s) => s.id === id);
    if (idx === -1) state.sources.push(item); else state.sources[idx] = { ...state.sources[idx], ...item };
    saveState();
    return sendJson(res, 200, item);
  }
  if (method === 'DELETE' && p.startsWith('/api/sources/')) {
    const id = p.slice('/api/sources/'.length);
    const idx = state.sources.findIndex((s) => s.id === id);
    if (idx === -1) return sendJson(res, 404, { error: '源不存在' });
    state.sources.splice(idx, 1);
    cache.delete(id);
    saveState();
    broadcast({ type: 'sources-changed' });
    return sendJson(res, 200, { deleted: id });
  }

  // 刷新：服务端拉订阅 + 自动取节目单
  const refreshMatch = p.match(/^\/api\/refresh\/([^/]+)$/);
  if (method === 'POST' && refreshMatch) {
    const src = state.sources.find((s) => s.id === refreshMatch[1]);
    if (!src) return sendJson(res, 404, { error: '源不存在' });
    try { return sendJson(res, 200, await refreshSource(src)); }
    catch (e) { return sendJson(res, 502, { error: String(e.message || e) }); }
  }
  if (method === 'GET' && p.startsWith('/api/cache/')) {
    const r = cache.get(p.slice('/api/cache/'.length));
    return r ? sendJson(res, 200, r) : sendJson(res, 404, { error: '暂无缓存，请先刷新' });
  }

  // 通用代理（前端可直接验证任意订阅文本）
  if (method === 'GET' && p === '/api/fetch') {
    const target = u.searchParams.get('url');
    try { return sendText(res, 200, await fetchText(target), 'text/plain; charset=utf-8'); }
    catch (e) { return sendJson(res, 502, { error: String(e.message || e) }); }
  }

  // TV 配对
  if (method === 'POST' && p === '/api/pair/open') {
    const code = String(100000 + Math.floor(Math.random() * 900000));
    pairCodes.set(code, { createdAt: Date.now(), used: false });
    return sendJson(res, 200, {
      code,
      pairUrl: 'http://' + pairHost() + ':' + PORT + '/pair?code=' + code,
      host: pairHost(), crossDevice: BIND === '0.0.0.0',
      hint: BIND === '0.0.0.0' ? '已允许局域网访问' : '当前仅本机可访问；跨设备需以 IPTV_BIND=0.0.0.0 启动',
    });
  }
  if (method === 'GET' && p === '/pair') {
    const code = u.searchParams.get('code') || '';
    if (!pairCodes.has(code)) return sendText(res, 400, '配对码无效或已过期', 'text/html; charset=utf-8');
    return sendText(res, 200, pairPage(code), 'text/html; charset=utf-8');
  }
  if (method === 'POST' && p === '/api/pair/submit') {
    let body;
    try { body = await readBody(req); } catch (e) { return sendJson(res, 400, { error: e.message }); }
    const rec = pairCodes.get(String(body.code || ''));
    if (!rec || rec.used) return sendJson(res, 400, { error: '配对码无效或已使用' });
    const n = IPTV.normalizeSourceUrl(body.url);
    if (!n.ok) return sendJson(res, 400, { error: n.error });
    const src = {
      id: newId(), name: String(body.name || '').trim() || '扫码添加的源',
      url: n.url, epgUrl: '', demo: false, addedAt: Date.now(), viaPair: true,
    };
    state.sources.push(src);
    rec.used = true;
    saveState();
    broadcast({ type: 'source-added', source: { id: src.id, name: src.name, url: src.url } });
    return sendJson(res, 200, { ok: true, source: { id: src.id, name: src.name } });
  }

  return sendJson(res, 404, { error: 'Not Found' });
}

// ---------------------------------------------------------------- 启动

function start() {
  const server = http.createServer((req, res) => {
    handle(req, res).catch((err) => {
      console.error('[请求异常]', err);
      if (!res.headersSent) sendJson(res, 500, { error: String(err.message || err) });
    });
  });
  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE' && PORT < 8800) { console.log(`端口 ${PORT} 占用，尝试 ${PORT + 1}`); PORT += 1; start(); return; }
    console.error('[启动失败]', err.message); process.exit(1);
  });
  server.listen(PORT, BIND, () => {
    console.log('');
    console.log('  IPTV 空壳播放器 · Web 原型');
    console.log(`  → http://127.0.0.1:${PORT}`);
    console.log(`  监听：${BIND}  局域网地址：${LAN_IP}`);
    console.log(`  数据：${DATA_FILE}`);
    console.log('  Ctrl+C 停止');
    console.log('');
  });
}

try {
  ensureData();
  state = loadState();
  start();
} catch (err) {
  console.error('[初始化失败] 未覆盖数据文件：', err.message);
  process.exit(1);
}
