/* 合成演示源：不含任何真实频道或可播放地址，仅用于跑通解析与 UI 链路。
 * 流地址由服务端返回 404，用来验证播放失败时的错误提示。
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.IPTVDemo = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const CHANNELS = [
    { id: 'demo.ch1', name: '演示频道一', group: '演示分组' },
    { id: 'demo.ch2', name: '演示频道二', group: '演示分组' },
    { id: 'demo.ch3', name: '演示频道三', group: '另一分组' },
  ];
  const TITLES = ['当前时段节目', '下一时段节目', '稍后时段节目'];

  function demoM3u(base) {
    const b = String(base).replace(/\/$/, '');
    const epg = b + '/demo/epg.xml';
    const lines = [
      '#EXTM3U x-tvg-url="' + epg + '" url-tvg="' + epg + '"',
      '# === 合成演示数据，非真实频道 ===',
    ];
    CHANNELS.forEach((ch, i) => {
      lines.push('#EXTINF:-1 tvg-id="' + ch.id + '" tvg-name="' + ch.name +
        '" group-title="' + ch.group + '",' + ch.name);
      if (i === 1) lines.push('#EXTVLCOPT:http-user-agent=IPTVPlayer-Demo/0.1');
      lines.push(b + '/demo/stream/' + ch.id + '.m3u8');
    });
    return lines.join('\n') + '\n';
  }

  function fmtUtc(ms) {
    const d = new Date(ms);
    const p = (n) => String(n).padStart(2, '0');
    return String(d.getUTCFullYear()) + p(d.getUTCMonth() + 1) + p(d.getUTCDate()) +
      p(d.getUTCHours()) + p(d.getUTCMinutes()) + p(d.getUTCSeconds()) + ' +0000';
  }

  function demoXmltv(now) {
    const t = now == null ? Date.now() : now;
    const slot = 3600000;
    const base = Math.floor(t / slot) * slot;
    const out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv>'];
    for (const ch of CHANNELS) {
      out.push('  <channel id="' + ch.id + '">');
      out.push('    <display-name>' + ch.name + '</display-name>');
      out.push('  </channel>');
    }
    for (const ch of CHANNELS) {
      for (let i = 0; i < 3; i++) {
        const start = base + i * slot;
        out.push('  <programme start="' + fmtUtc(start) + '" stop="' + fmtUtc(start + slot) +
          '" channel="' + ch.id + '">');
        out.push('    <title>' + TITLES[i] + ' · ' + ch.name + '</title>');
        out.push('    <desc>合成节目单条目，用于验证 XMLTV 对表</desc>');
        out.push('  </programme>');
      }
    }
    out.push('</tv>');
    return out.join('\n') + '\n';
  }

  return { CHANNELS, demoM3u, demoXmltv };
});
