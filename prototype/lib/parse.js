/* 共享数据层：M3U / XMLTV 解析与 EPG 地址推导。
 * 浏览器通过 <script> 得到全局 IPTVData；Node 通过 require 得到同一份实现。
 * Android 工程 core/ 下的 Kotlin 实现逐条对应本文件的规则。
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.IPTVData = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const ATTR_RE = /([\w:.-]+)\s*=\s*"([^"]*)"/g;

  function parseAttrs(s) {
    const out = {};
    const re = new RegExp(ATTR_RE.source, 'g');
    let m;
    while ((m = re.exec(String(s || '')))) out[m[1].toLowerCase()] = m[2];
    return out;
  }

  function decodeXml(s) {
    return String(s || '')
      .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
      .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)))
      .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"').replace(/&apos;/g, "'")
      .replace(/&amp;/g, '&')
      .trim();
  }

  /* M3U：支持 x-tvg-url / url-tvg、tvg-id / tvg-name / group-title / tvg-logo、
   * #EXTVLCOPT 的 UA 与 Referer。属性内的逗号不会干扰频道名切分。 */
  function parseM3u(text) {
    const lines = String(text || '').split(/\r?\n/);
    let epgUrl = null;
    const channels = [];
    const warnings = [];
    let pending = null;
    let seq = 0;

    for (const raw of lines) {
      const line = raw.trim();
      if (!line) continue;

      if (/^#EXTM3U\b/i.test(line)) {
        const a = parseAttrs(line);
        epgUrl = a['x-tvg-url'] || a['url-tvg'] || null;
        continue;
      }
      if (/^#EXTINF\b/i.test(line)) {
        const attrs = parseAttrs(line);
        const rest = line.replace(new RegExp(ATTR_RE.source, 'g'), '');
        const comma = rest.indexOf(',');
        const name = comma >= 0 ? rest.slice(comma + 1).trim() : '';
        pending = { attrs, name, userAgent: '', referrer: '' };
        continue;
      }
      if (/^#EXTVLCOPT:/i.test(line)) {
        const kv = line.slice(line.indexOf(':') + 1);
        const eq = kv.indexOf('=');
        if (eq > 0 && pending) {
          const k = kv.slice(0, eq).toLowerCase();
          const v = kv.slice(eq + 1).trim();
          if (k === 'http-user-agent') pending.userAgent = v;
          else if (k === 'http-referrer' || k === 'http-referer') pending.referrer = v;
        }
        continue;
      }
      if (line.startsWith('#')) continue;

      if (pending) {
        const a = pending.attrs;
        const display = pending.name || a['tvg-name'] || line;
        channels.push({
          id: 'ch-' + (++seq),
          name: display,
          group: a['group-title'] || '未分组',
          logo: a['tvg-logo'] || '',
          tvgId: a['tvg-id'] || a['tvg-name'] || display,
          url: line,
          userAgent: pending.userAgent,
          referrer: pending.referrer,
        });
        pending = null;
      } else {
        warnings.push('孤立的流地址：' + line.slice(0, 60));
      }
    }
    return { epgUrl, channels, warnings };
  }

  /* EPG 地址：优先 M3U 头声明；否则从订阅 URL 同源推导 /epg.xml。
   * 关键点：推导必须保留 hd-iptv 的访问密码路径段，例如
   * /tv7Kd2x/playlist.m3u -> /tv7Kd2x/epg.xml。 */
  function resolveEpgUrl(subscriptionUrl, m3uEpgUrl) {
    let base;
    try { base = new URL(subscriptionUrl); } catch { return null; }
    if (!/^https?:$/.test(base.protocol)) return null;

    const declared = String(m3uEpgUrl || '').trim();
    if (declared) {
      try {
        const u = new URL(declared, base);
        if (/^https?:$/.test(u.protocol)) return u.toString();
      } catch { /* 声明非法，走同源推导 */ }
    }
    const last = base.pathname.split('/').pop() || '';
    const isFile = /\.(m3u8?|txt|xml|json)$/i.test(last) || last === 'json' || last === 'txt';
    const dir = isFile
      ? base.pathname.slice(0, base.pathname.length - last.length)
      : (base.pathname.endsWith('/') ? base.pathname : base.pathname + '/');
    try { return new URL(dir + 'epg.xml', base.origin).toString(); } catch { return null; }
  }

  /* XMLTV 时间：支持 20261124200000 +0800 与 ISO 8601 两种写法。 */
  function parseXmltvTime(s) {
    const v = String(s || '').trim();
    if (!v) return NaN;
    const m = v.match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?:\s*([+-]\d{4}))?/);
    if (m) {
      const Y = +m[1], M = +m[2], D = +m[3], h = +m[4], mi = +m[5], se = +m[6];
      if (!m[7]) return new Date(Y, M - 1, D, h, mi, se).getTime();
      let ms = Date.UTC(Y, M - 1, D, h, mi, se);
      const sign = m[7][0] === '-' ? -1 : 1;
      const off = (+m[7].slice(1, 3) * 60 + +m[7].slice(3)) * 60000;
      return ms - sign * off;
    }
    const t = Date.parse(v);
    return Number.isNaN(t) ? NaN : t;
  }

  function parseXmltv(xml) {
    const text = String(xml || '');
    const channels = {};
    const byName = {};
    const programmes = {};
    const chRe = /<channel\b([^>]*)>([\s\S]*?)<\/channel\s*>/gi;
    const prRe = /<programme\b([^>]*)>([\s\S]*?)<\/programme\s*>/gi;
    let m;

    while ((m = chRe.exec(text))) {
      const id = parseAttrs(m[1]).id;
      if (!id) continue;
      const dn = /<display-name[^>]*>([\s\S]*?)<\/display-name\s*>/i.exec(m[2]);
      const name = dn ? decodeXml(dn[1]) : id;
      channels[id] = name;
      if (name) byName[name] = id;
    }
    while ((m = prRe.exec(text))) {
      const a = parseAttrs(m[1]);
      if (!a.channel) continue;
      const start = parseXmltvTime(a.start);
      if (Number.isNaN(start)) continue;
      let stop = parseXmltvTime(a.stop);
      if (Number.isNaN(stop)) stop = start + 3600000;
      const t = /<title[^>]*>([\s\S]*?)<\/title\s*>/i.exec(m[2]);
      const d = /<desc[^>]*>([\s\S]*?)<\/desc\s*>/i.exec(m[2]);
      (programmes[a.channel] || (programmes[a.channel] = [])).push({
        start, stop,
        title: t ? decodeXml(t[1]) : '（无标题）',
        desc: d ? decodeXml(d[1]) : '',
      });
    }
    for (const k of Object.keys(programmes)) programmes[k].sort((x, y) => x.start - y.start);
    return { channels, byName, programmes };
  }

  /* 对表顺序：tvg-id -> display-name 反查 -> 频道名。与 hd-iptv/epg.py 的 mapping 一致。 */
  function nowNext(epg, tvgId, channelName, now) {
    const t = now == null ? Date.now() : now;
    if (!epg || !epg.programmes) return { current: null, next: null, matched: false };
    const ids = [];
    if (tvgId) ids.push(tvgId);
    const mapped = epg.byName[tvgId] || epg.byName[channelName];
    if (mapped && ids.indexOf(mapped) === -1) ids.push(mapped);
    if (channelName && ids.indexOf(channelName) === -1) ids.push(channelName);

    let list = [];
    for (const id of ids) {
      if (epg.programmes[id] && epg.programmes[id].length) { list = epg.programmes[id]; break; }
    }
    if (!list.length) return { current: null, next: null, matched: false };

    let current = null;
    for (const p of list) {
      if (p.start <= t && t < p.stop) { current = p; break; }
    }
    const after = current ? current.stop : t;
    let next = null;
    for (const p of list) {
      if (p.start >= after) { next = p; break; }
    }
    return { current, next, matched: true };
  }

  function groupChannels(channels) {
    const order = [];
    const map = new Map();
    for (const ch of channels || []) {
      const g = ch.group || '未分组';
      if (!map.has(g)) { map.set(g, []); order.push(g); }
      map.get(g).push(ch);
    }
    return order.map((name) => ({ name, channels: map.get(name) }));
  }

  function normalizeSourceUrl(raw) {
    const s = String(raw || '').trim();
    if (!s) return { ok: false, error: '地址为空' };
    let u;
    try { u = new URL(s); } catch { return { ok: false, error: '不是合法的 URL' }; }
    if (!/^https?:$/.test(u.protocol)) return { ok: false, error: '仅支持 http / https' };
    return { ok: true, url: u.toString() };
  }

  return {
    parseAttrs, parseM3u, resolveEpgUrl, parseXmltvTime,
    parseXmltv, nowNext, groupChannels, normalizeSourceUrl,
  };
});
