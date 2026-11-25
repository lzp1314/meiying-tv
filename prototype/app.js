'use strict';

/* IPTV 空壳播放器 · Web 原型前端
 * 数据层在服务端 /lib/parse.js 与 /app.js 共用同一份规则；这里只负责展示与交互。
 */
const S = {
  sources: [], activeId: null, result: null, mode: 'mobile',
  hls: null, scanStream: null, scanTimer: null, scanDetector: null, pairCode: null,
};

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function attr(v) { return esc(v); }
async function api(url, options) {
  const r = await fetch(url, options);
  const d = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(d.error || `HTTP ${r.status}`);
  return d;
}
function toast(msg, kind) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.className = 'toast' + (kind ? ' ' + kind : '');
  el.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { el.hidden = true; }, 3400);
}
function newId() { return 'src-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 7); }
function fmt(ts) { return ts ? new Date(ts).toLocaleString('zh-CN', { hour12: false }) : '—'; }
function open(id) { document.getElementById(id).hidden = false; }
function close(id) { document.getElementById(id).hidden = true; }

// ---------------------------------------------------------------- 数据

async function loadState() {
  try {
    const st = await api('/api/state');
    S.sources = st.sources || [];
    if (!S.sources.some((s) => s.id === S.activeId)) {
      S.activeId = S.sources.length ? S.sources[0].id : null;
      S.result = null;
    }
    renderSources();
    document.getElementById('srcCount').textContent = S.sources.length;
    document.getElementById('subline').textContent = S.sources.length
      ? `${S.sources.length} 个订阅源 · 应用不内置任何频道`
      : '源列表为空，添加一个订阅地址开始';
  } catch (e) { toast('读取源列表失败：' + e.message, 'bad'); }
}

async function refresh(id) {
  if (!id) return;
  const hint = document.getElementById('diagHint');
  hint.textContent = '正在拉取订阅与节目单…';
  try {
    const r = await api(`/api/refresh/${encodeURIComponent(id)}`, { method: 'POST' });
    S.activeId = id; S.result = r;
    renderAll();
    toast(`解析成功：${r.channelCount} 个频道，节目单匹配 ${r.epgMatchedCount} 个`, 'ok');
  } catch (e) {
    hint.textContent = '刷新失败';
    toast('刷新失败：' + e.message, 'bad');
  }
}

async function saveSource(payload) {
  const n = window.IPTVData.normalizeSourceUrl(payload.url);
  if (!n.ok) { toast(n.error, 'bad'); return; }
  try {
    const src = await api(`/api/sources/${encodeURIComponent(newId())}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: payload.name, url: n.url, epgUrl: payload.epgUrl || '' }),
    });
    close('addModal');
    await loadState();
    S.activeId = src.id;
    renderSources();
    toast('已保存，正在刷新…');
    await refresh(src.id);
  } catch (e) { toast('保存失败：' + e.message, 'bad'); }
}

// ---------------------------------------------------------------- 渲染

function renderAll() { renderSources(); renderDiag(); renderChannels(); }

function renderSources() {
  const el = document.getElementById('sourceList');
  document.getElementById('srcCount').textContent = S.sources.length;
  if (!S.sources.length) {
    el.innerHTML = '<div class="empty">还没有订阅源<br><br>点「载入演示源」可跑通完整链路</div>';
    return;
  }
  el.innerHTML = S.sources.map((s) => `
    <div class="source-card ${s.id === S.activeId ? 'active' : ''}" data-src="${attr(s.id)}">
      <div class="s-top"><span class="s-name">${esc(s.name)}</span>
        ${s.demo ? '<span class="badge">演示</span>' : ''}</div>
      <div class="s-url">${esc(s.url)}</div>
      <div class="s-meta">${s.channelCount ? s.channelCount + ' 个频道 · ' : ''}${s.lastOk ? '上次成功 ' + fmt(s.lastOk) : '尚未刷新'}</div>
      <div class="s-actions">
        <button class="btn btn-sm focusable" data-act="refresh" data-id="${attr(s.id)}">刷新</button>
        <button class="btn btn-sm focusable" data-act="edit" data-id="${attr(s.id)}">编辑</button>
        <button class="btn btn-sm btn-danger focusable" data-act="del" data-id="${attr(s.id)}">删除</button>
      </div>
    </div>`).join('');
}

function renderDiag() {
  const body = document.getElementById('diagBody');
  const hint = document.getElementById('diagHint');
  const r = S.result;
  if (!r) {
    hint.textContent = '尚未刷新任何源';
    body.innerHTML = '<div class="empty">添加源后点「刷新」，这里会显示 M3U 解析、EPG 自动发现与对表结果。</div>';
    return;
  }
  hint.textContent = '抓取于 ' + fmt(r.fetchedAt);
  const sourceKind = r.declaredEpgUrl ? 'M3U 头 x-tvg-url 声明' : '订阅 URL 同源推导 /epg.xml';
  const notes = [];
  if (r.epgError) notes.push('节目单拉取失败：' + r.epgError);
  if (r.warnings && r.warnings.length) notes.push('解析警告：' + r.warnings.slice(0, 3).join('；'));
  body.innerHTML = `
    <div class="diag-grid">
      <div class="diag-cell"><div class="k">频道总数</div><div class="v">${r.channelCount}</div></div>
      <div class="diag-cell"><div class="k">分组数</div><div class="v">${r.groups.length}</div></div>
      <div class="diag-cell"><div class="k">EPG 频道</div><div class="v">${r.epgChannelCount}</div></div>
      <div class="diag-cell"><div class="k">对上节目单</div><div class="v ${r.epgMatchedCount ? '' : 'warn'}">${r.epgMatchedCount}</div></div>
    </div>
    <div class="diag-rows">
      <div class="row"><span class="k">订阅地址</span><span class="v">${esc(r.subscriptionUrl)}</span></div>
      <div class="row"><span class="k">M3U 声明 EPG</span><span class="v">${esc(r.declaredEpgUrl || '（未声明）')}</span></div>
      <div class="row"><span class="k">实际使用 EPG</span><span class="v">${esc(r.epgUrl || '（无）')}</span></div>
      <div class="row"><span class="k">EPG 来源</span><span class="v">${esc(sourceKind)}</span></div>
    </div>
    ${notes.length ? `<div class="diag-notes">${notes.map((n) => esc(n)).join('<br>')}</div>` : ''}`;
}

function renderChannels() {
  const area = document.getElementById('channelArea');
  document.getElementById('chHint').textContent = S.result ? S.result.channelCount + ' 个频道' : '0 个频道';
  if (!S.result) { area.innerHTML = '<div class="empty">暂无频道</div>'; return; }
  area.innerHTML = S.result.groups.map((g) => `
    <div class="ch-group"><h3>${esc(g.name)} <span class="n">${g.channels.length} 个</span></h3>
      <div class="ch-grid">${g.channels.map((c) => `
        <button class="ch-card focusable" data-act="play" data-id="${attr(c.id)}">
          <div class="c-name">${c.logo ? `<img class="c-logo" src="${attr(c.logo)}" alt="" onerror="this.style.display='none'">` : ''}${esc(c.name)}</div>
          <div class="c-now">${c.current ? '正在：' + esc(c.current) : '当前节目：—'}</div>
          <div class="c-next">${c.next ? '接下来：' + esc(c.next) : '接下来：—'}</div>
          <div class="c-url">${esc(c.url)}</div>
          ${c.userAgent ? `<div class="c-ua">解析到 UA：${esc(c.userAgent)}（浏览器不可改写，Android 端会应用）</div>` : ''}
        </button>`).join('')}</div></div>`).join('');
}

// ---------------------------------------------------------------- 播放

function play(ch) {
  const video = document.getElementById('video');
  const info = document.getElementById('playerInfo');
  const hint = document.getElementById('playerHint');
  if (S.hls) { S.hls.destroy(); S.hls = null; }
  video.removeAttribute('src'); video.load();
  hint.textContent = '正在起播：' + ch.name;
  info.className = 'player-info';
  info.innerHTML = `频道：${esc(ch.name)}<br>地址：${esc(ch.url)}` +
    (ch.userAgent ? `<br>原生端将使用 UA：${esc(ch.userAgent)}` : '') +
    (ch.referrer ? `<br>原生端将使用 Referer：${esc(ch.referrer)}` : '');

  if (!window.Hls) {
    info.className = 'player-info bad';
    info.innerHTML += '<br><br>hls.js 未加载（CDN 不可达）。数据层功能不受影响；原生工程使用 Media3，不依赖此库。';
    return;
  }
  if (!window.Hls.isSupported()) {
    info.className = 'player-info bad';
    info.innerHTML += '<br><br>当前浏览器不支持 MSE/HLS；原生端使用 Media3，不受此限制。';
    return;
  }
  const hls = new window.Hls({ enableWorker: true, lowLatencyMode: false });
  S.hls = hls;
  hls.on(window.Hls.Events.MANIFEST_PARSED, () => {
    info.className = 'player-info ok';
    info.innerHTML += '<br><br>清单解析成功，尝试起播。';
    video.play().catch(() => {});
  });
  hls.on(window.Hls.Events.ERROR, (_, data) => {
    if (!data.fatal) return;
    info.className = 'player-info bad';
    info.innerHTML += `<br><br>播放失败：${esc(data.type)} / ${esc(data.details || '')}。` +
      (data.details === 'manifestLoadError' ? '演示源刻意不提供可播流，此结果符合预期。' : '请检查流地址与网络。');
  });
  hls.loadSource(ch.url);
  hls.attachMedia(video);
}

// ---------------------------------------------------------------- 扫码

async function startScan() {
  const status = document.getElementById('scanStatus');
  const video = document.getElementById('scanVideo');
  if (!window.BarcodeDetector) {
    status.textContent = '当前浏览器不支持 BarcodeDetector。请在下方手动粘贴扫描结果（原生工程使用 ML Kit，不受此限制）。';
    return;
  }
  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    status.textContent = '摄像头不可用（需 localhost 或 HTTPS）。请在下方手动粘贴。';
    return;
  }
  try {
    S.scanDetector = new window.BarcodeDetector({ formats: ['qr_code'] });
    S.scanStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
    video.srcObject = S.scanStream;
    await video.play();
    status.textContent = '摄像头已启动，把二维码对准取景框…';
    const tick = async () => {
      if (!S.scanStream) return;
      try {
        const codes = await S.scanDetector.detect(video);
        if (codes.length) {
          const value = codes[0].rawValue;
          stopScan();
          close('scanModal');
          document.getElementById('inUrl').value = value;
          open('addModal');
          toast('已识别二维码，请确认后保存');
          return;
        }
      } catch { /* 单帧失败，继续 */ }
      S.scanTimer = setTimeout(tick, 250);
    };
    tick();
  } catch (e) {
    status.textContent = '摄像头启动失败：' + e.message + '。请在下方手动粘贴。';
  }
}
function stopScan() {
  clearTimeout(S.scanTimer); S.scanTimer = null;
  if (S.scanStream) { S.scanStream.getTracks().forEach((t) => t.stop()); S.scanStream = null; }
  const v = document.getElementById('scanVideo');
  if (v) v.srcObject = null;
}

// ---------------------------------------------------------------- TV 配对

async function openPair() {
  open('pairModal');
  const qrBox = document.getElementById('qrBox');
  const state = document.getElementById('pairState');
  qrBox.innerHTML = '<div class="empty">正在生成二维码…</div>';
  try {
    const d = await api('/api/pair/open', { method: 'POST' });
    S.pairCode = d.code;
    document.getElementById('pairCode').textContent = d.code;
    document.getElementById('pairUrl').textContent = d.pairUrl;
    document.getElementById('pairScope').textContent = d.hint;
    state.textContent = '等待手机推送…'; state.className = 'pair-state';
    if (window.qrcode) {
      const qr = window.qrcode(0, 'M');
      qr.addData(d.pairUrl); qr.make();
      qrBox.innerHTML = qr.createSvgTag(4, 0);
    } else {
      qrBox.innerHTML = '<div class="empty">二维码库未加载<br>请用 6 位配对码<br>或下方地址</div>';
    }
    toast(d.crossDevice ? '已开启局域网配对' : '当前仅本机可配对，跨设备见弹窗说明');
  } catch (e) {
    state.textContent = '配对失败：' + e.message; state.className = 'pair-state';
  }
}

// ---------------------------------------------------------------- SSE

function connectSSE() {
  const dot = document.getElementById('connDot');
  const text = document.getElementById('connText');
  const es = new EventSource('/api/events');
  es.onopen = () => { dot.className = 'dot ok'; text.textContent = '已连接本机服务'; };
  es.onerror = () => { dot.className = 'dot bad'; text.textContent = '连接断开，重连中'; };
  es.onmessage = async (ev) => {
    let d; try { d = JSON.parse(ev.data); } catch { return; }
    if (d.type === 'source-added' || d.type === 'sources-changed') {
      await loadState();
      if (d.type === 'source-added') {
        const st = document.getElementById('pairState');
        if (st && !document.getElementById('pairModal').hidden) {
          st.className = 'pair-state ok';
          st.textContent = `已收到：${d.source.name}`;
        }
        toast('手机已推送新订阅源');
        await refresh(d.source.id);
      }
    }
  };
}

// ---------------------------------------------------------------- TV 模式方向键

function focusables() {
  return [...document.querySelectorAll('.focusable, .ch-card')]
    .filter((el) => el.offsetParent !== null);
}
function moveFocus(delta) {
  const list = focusables();
  if (!list.length) return;
  let i = list.indexOf(document.activeElement);
  if (i < 0) i = 0;
  const next = Math.max(0, Math.min(list.length - 1, i + delta));
  list[next].focus();
}
function setMode(mode) {
  S.mode = mode;
  document.body.classList.toggle('tv-mode', mode === 'tv');
  document.querySelectorAll('#modeSeg button').forEach((b) => b.classList.toggle('active', b.dataset.mode === mode));
  document.getElementById('playerHint').textContent = mode === 'tv'
    ? 'TV 模式：方向键移动焦点，回车选中（原型模拟 D-pad）'
    : '空壳：仅做起播与错误提示，不做解码定制';
  if (mode === 'tv') setTimeout(() => moveFocus(0), 0);
}

// ---------------------------------------------------------------- 事件

function bind() {
  document.getElementById('btnAdd').onclick = () => {
    document.getElementById('inUrl').value = '';
    document.getElementById('inName').value = '';
    document.getElementById('inEpg').value = '';
    document.getElementById('addTitle').textContent = '添加订阅源';
    open('addModal');
  };
  document.getElementById('btnSaveSource').onclick = () => saveSource({
    url: document.getElementById('inUrl').value,
    name: document.getElementById('inName').value,
    epgUrl: document.getElementById('inEpg').value,
  });
  document.getElementById('btnScan').onclick = () => { open('scanModal'); startScan(); };
  document.getElementById('btnInlineScan').onclick = () => { close('addModal'); open('scanModal'); startScan(); };
  document.getElementById('btnScanStop').onclick = () => { stopScan(); close('scanModal'); };
  document.getElementById('btnScanUse').onclick = () => {
    const v = document.getElementById('scanManual').value.trim();
    const n = window.IPTVData.normalizeSourceUrl(v);
    if (!n.ok) { toast(n.error, 'bad'); return; }
    stopScan(); close('scanModal');
    document.getElementById('inUrl').value = v; open('addModal');
  };
  document.getElementById('btnPair').onclick = openPair;
  document.getElementById('btnDemo').onclick = async () => {
    try {
      await api('/api/demo/add', { method: 'POST' });
      await loadState();
      toast('已载入合成演示源，正在验证数据层…');
      await refresh('demo-source');
    } catch (e) { toast('载入失败：' + e.message, 'bad'); }
  };

  document.getElementById('modeSeg').onclick = (e) => {
    const b = e.target.closest('[data-mode]'); if (b) setMode(b.dataset.mode);
  };
  document.addEventListener('click', (e) => {
    const c = e.target.closest('[data-close]');
    if (c) {
      const id = c.dataset.close;
      close(id); if (id === 'scanModal') stopScan();
      return;
    }
    const card = e.target.closest('.source-card[data-src]');
    if (card && !e.target.closest('button')) {
      S.activeId = card.dataset.src; renderSources();
      const cached = S.result && S.result.sourceId === S.activeId;
      if (!cached) { S.result = null; renderDiag(); renderChannels(); }
      return;
    }
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const act = b.dataset.act, id = b.dataset.id;
    if (act === 'refresh') refresh(id);
    else if (act === 'edit') {
      const s = S.sources.find((x) => x.id === id); if (!s) return;
      document.getElementById('inUrl').value = s.url;
      document.getElementById('inName').value = s.name;
      document.getElementById('inEpg').value = s.epgUrl || '';
      document.getElementById('addTitle').textContent = '编辑订阅源（保存为新条目）';
      open('addModal');
    } else if (act === 'del') {
      if (!confirm('确认删除该订阅源？')) return;
      api(`/api/sources/${encodeURIComponent(id)}`, { method: 'DELETE' })
        .then(() => { if (S.activeId === id) { S.activeId = null; S.result = null; } loadState(); renderAll(); toast('已删除', 'ok'); })
        .catch((err) => toast('删除失败：' + err.message, 'bad'));
    } else if (act === 'play') {
      const ch = S.result && S.result.channels.find((x) => x.id === id);
      if (ch) play(ch);
    }
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      ['addModal', 'scanModal', 'pairModal'].forEach((id) => close(id));
      stopScan();
    }
    if (S.mode !== 'tv') return;
    if (e.key === 'ArrowRight') { e.preventDefault(); moveFocus(1); }
    else if (e.key === 'ArrowLeft') { e.preventDefault(); moveFocus(-1); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); moveFocus(4); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); moveFocus(-4); }
    else if (e.key === 'Enter' && document.activeElement && document.activeElement.click) {
      e.preventDefault(); document.activeElement.click();
    }
  });
  window.addEventListener('beforeunload', stopScan);
}

(async function init() {
  bind();
  await loadState();
  connectSSE();
  setMode('mobile');
  try {
    const h = await api('/api/health');
    document.getElementById('dataFileHint').textContent = `本机 ${h.bind}:${h.port} · 局域网 ${h.lanIp}`;
  } catch { /* 健康检查失败不影响界面 */ }
})();
