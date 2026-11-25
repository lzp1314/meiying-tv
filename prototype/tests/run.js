'use strict';

/* 数据层单元测试 + 原型端到端冒烟。
 * 用法：node prototype/tests/run.js [port]
 */
const fs = require('node:fs');
const path = require('node:path');
const IPTV = require('../lib/parse.js');
const DEMO = require('../lib/demo.js');

const PORT = Number(process.argv[2]) || Number(process.env.IPTV_PORT) || 8790;
const BASE = 'http://127.0.0.1:' + PORT;
const fixture = fs.readFileSync(path.join(__dirname, 'fixtures', 'sample.m3u'), 'utf8');
const out = [];
let failed = 0;
function ck(name, cond, detail) {
  if (!cond) failed++;
  out.push((cond ? 'PASS' : 'FAIL') + ' | ' + name + (detail !== undefined ? ' | ' + detail : ''));
}
async function req(p, opts) {
  const r = await fetch(BASE + p, opts);
  const t = await r.text();
  let j = null; try { j = JSON.parse(t); } catch { /* 非 JSON */ }
  return { s: r.status, j, t, ct: r.headers.get('content-type') || '' };
}

async function unitTests() {
  // 真实 hd-iptv 样本
  const real = IPTV.parseM3u(fixture);
  ck('真实样本解析出频道', real.channels.length > 0, real.channels.length + ' 个');
  ck('真实样本频道有分组', real.channels.every((c) => c.group && c.group !== '未分组'),
    [...new Set(real.channels.map((c) => c.group))].length + ' 组');
  ck('真实样本流地址为 http(s)', real.channels.every((c) => /^https?:\/\//.test(c.url)));
  ck('真实样本无孤立地址', real.warnings.length === 0, real.warnings.length + ' 条');

  // 合成 M3U：属性含逗号、EXTVLCOPT、x-tvg-url
  const synthetic = [
    '#EXTM3U x-tvg-url="http://nas:9797/pwd/epg.xml" url-tvg="http://nas:9797/pwd/epg.xml"',
    '#EXTINF:-1 tvg-id="a.ch1" tvg-name="频道甲" group-title="卫视,高清",频道甲',
    'http://nas/live/1.m3u8',
    '#EXTINF:-1 tvg-id="a.ch2" group-title="体育",频道乙',
    '#EXTVLCOPT:http-user-agent=IPTVPlayer/0.1',
    '#EXTVLCOPT:http-referrer=http://nas/',
    'http://nas/live/2.ts',
  ].join('\n');
  const s = IPTV.parseM3u(synthetic);
  ck('M3U 头 x-tvg-url 提取', s.epgUrl === 'http://nas:9797/pwd/epg.xml', String(s.epgUrl));
  ck('属性内逗号不切分频道名', s.channels[0].name === '频道甲', s.channels[0].name);
  ck('group-title 保留逗号', s.channels[0].group === '卫视,高清', s.channels[0].group);
  ck('tvg-id 解析', s.channels[0].tvgId === 'a.ch1', s.channels[0].tvgId);
  ck('EXTVLCOPT UA 解析', s.channels[1].userAgent === 'IPTVPlayer/0.1', s.channels[1].userAgent);
  ck('EXTVLCOPT Referrer 解析', s.channels[1].referrer === 'http://nas/', s.channels[1].referrer);
  ck('分组聚合', IPTV.groupChannels(s.channels).length === 2);

  // EPG 地址推导（关键：保留访问密码路径段）
  ck('带访问密码的订阅推导 EPG',
    IPTV.resolveEpgUrl('http://NAS:9797/tv7Kd2x/playlist.m3u', null) === 'http://nas:9797/tv7Kd2x/epg.xml',
    IPTV.resolveEpgUrl('http://NAS:9797/tv7Kd2x/playlist.m3u', null));
  ck('无密码订阅推导 EPG',
    IPTV.resolveEpgUrl('http://NAS:9797/playlist.m3u', null) === 'http://nas:9797/epg.xml',
    IPTV.resolveEpgUrl('http://NAS:9797/playlist.m3u', null));
  ck('M3U 声明优先于同源推导',
    IPTV.resolveEpgUrl('http://NAS:9797/pwd/playlist.m3u', 'https://epg.example.com/cn.xml') === 'https://epg.example.com/cn.xml');
  ck('相对 EPG 声明按订阅解析',
    IPTV.resolveEpgUrl('http://NAS:9797/pwd/playlist.m3u', '../guide.xml') === 'http://nas:9797/guide.xml',
    IPTV.resolveEpgUrl('http://NAS:9797/pwd/playlist.m3u', '../guide.xml'));
  ck('非 http 订阅被拒绝', IPTV.resolveEpgUrl('file:///tmp/x.m3u', null) === null);
  ck('非法 URL 被拒绝', IPTV.normalizeSourceUrl('ftp://x/y').ok === false);

  // XMLTV 时间
  const withTz = IPTV.parseXmltvTime('20261124200000 +0800');
  const utc = Date.UTC(2026, 10, 24, 12, 0, 0);
  ck('XMLTV 带时区时间', withTz === utc, new Date(withTz).toISOString());
  ck('XMLTV ISO 时间', IPTV.parseXmltvTime('2026-11-24T20:00:00+08:00') === utc);
  ck('XMLTV 无时区时间不报 NaN', !Number.isNaN(IPTV.parseXmltvTime('20261124200000')));
  ck('空时间返回 NaN', Number.isNaN(IPTV.parseXmltvTime('')));

  // XMLTV 对表
  const now = Date.now();
  const xml = DEMO.demoXmltv(now);
  const epg = IPTV.parseXmltv(xml);
  ck('XMLTV 频道解析', Object.keys(epg.channels).length === 3, Object.keys(epg.channels).length + ' 个');
  ck('XMLTV 节目解析', Object.keys(epg.programmes).length === 3 && epg.programmes['demo.ch1'].length === 3);
  const nn = IPTV.nowNext(epg, 'demo.ch1', '演示频道一', now);
  ck('当前节目命中', !!nn.current && nn.current.title.indexOf('当前时段节目') === 0, nn.current && nn.current.title);
  ck('下一节目命中', !!nn.next && nn.next.title.indexOf('下一时段节目') === 0, nn.next && nn.next.title);
  const byName = IPTV.nowNext(epg, '演示频道二', '', now);
  ck('display-name 反查兜底', byName.matched === true && !!byName.current);
  ck('未匹配的 tvg-id 返回未命中', IPTV.nowNext(epg, 'not.exist', '', now).matched === false);
  ck('XML 实体解码', IPTV.parseXmltv('<tv><channel id="x"><display-name>A &amp; B</display-name></channel></tv>').channels.x === 'A & B');
}

async function e2eTests() {
  let health;
  try { health = await req('/api/health'); } catch (e) {
    ck('端到端：服务可达', false, e.message); return;
  }
  ck('端到端：服务健康检查', health.s === 200 && health.j.ok === true, `${health.j.bind}:${health.j.port}`);
  for (const p of ['/', '/app.css', '/app.js', '/lib/parse.js']) {
    const r = await req(p); ck('静态资源 ' + p, r.s === 200, String(r.s));
  }
  const badOrigin = await req('/api/state', { headers: { Origin: 'http://evil.example' } });
  ck('非法 Origin 拦截', badOrigin.s === 403, String(badOrigin.s));

  const demoM3u = await req('/demo/playlist.m3u');
  ck('演示订阅返回 M3U', demoM3u.s === 200 && demoM3u.t.includes('#EXTM3U') && demoM3u.t.includes('x-tvg-url'));
  const demoEpg = await req('/demo/epg.xml');
  ck('演示节目单返回 XMLTV', demoEpg.s === 200 && demoEpg.t.includes('<programme'));
  const demoStream = await req('/demo/stream/demo.ch1.m3u8');
  ck('演示流刻意不可播', demoStream.s === 404, String(demoStream.s));

  const added = await req('/api/demo/add', { method: 'POST' });
  ck('演示源写入源列表', added.s === 200 && added.j.id === 'demo-source');
  const refreshed = await req('/api/refresh/demo-source', { method: 'POST' });
  const r = refreshed.j;
  ck('刷新成功', refreshed.s === 200, refreshed.s === 200 ? '' : r.error);
  if (refreshed.s === 200) {
    ck('演示源频道数 = 3', r.channelCount === 3, String(r.channelCount));
    ck('EPG 地址来自 M3U 声明', r.declaredEpgUrl && r.epgUrl && r.epgUrl.endsWith('/demo/epg.xml'), r.epgUrl);
    ck('节目单对表全部命中', r.epgMatchedCount === 3, String(r.epgMatchedCount));
    ck('分组数 = 2', r.groups.length === 2, String(r.groups.length));
    ck('EXTVLCOPT UA 在刷新结果里保留', r.channels.some((c) => c.userAgent === 'IPTVPlayer-Demo/0.1'));
    ck('频道带当前节目', r.channels.every((c) => !!c.current), r.channels[0].current);
    ck('频道带下一节目', r.channels.every((c) => !!c.next), r.channels[0].next);
  }

  const cached = await req('/api/cache/demo-source');
  ck('刷新结果进入缓存', cached.s === 200 && cached.j.channelCount === 3);

  // CRUD
  const id = 'smoke-' + Date.now();
  const put = await req('/api/sources/' + id, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: '冒烟测试源', url: 'http://127.0.0.1:1/nope.m3u' }),
  });
  ck('新增源', put.s === 200 && put.j.id === id);
  const bad = await req('/api/sources/bad-' + id, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'x', url: 'ftp://x/y' }),
  });
  ck('非法订阅协议被拒绝', bad.s === 400, bad.j && bad.j.error);
  const failRefresh = await req('/api/refresh/' + id, { method: 'POST' });
  ck('不可达源给出明确错误', failRefresh.s === 502 && !!failRefresh.j.error, failRefresh.j && failRefresh.j.error);
  const del = await req('/api/sources/' + id, { method: 'DELETE' });
  ck('删除源', del.s === 200 && del.j.deleted === id);
  const state = await req('/api/state');
  ck('数据落盘且测试源已清理', state.j.sources.every((s) => s.id !== id));

  // 配对链路
  const pair = await req('/api/pair/open', { method: 'POST' });
  ck('生成配对码', pair.s === 200 && /^\d{6}$/.test(pair.j.code), pair.j && pair.j.code);
  if (pair.s === 200) {
    const page = await req('/pair?code=' + pair.j.code);
    ck('配对页可访问', page.s === 200 && page.t.includes('推送订阅源'));
    const submit = await req('/api/pair/submit', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: pair.j.code, url: BASE + '/demo/playlist.m3u', name: '配对推送源' }),
    });
    ck('手机推送订阅源成功', submit.s === 200 && submit.j.ok === true);
    const reuse = await req('/api/pair/submit', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: pair.j.code, url: BASE + '/demo/playlist.m3u' }),
    });
    ck('配对码一次性有效', reuse.s === 400, reuse.j && reuse.j.error);
    const after = await req('/api/state');
    const pushed = after.j.sources.find((s) => s.viaPair);
    ck('推送的源已持久化', !!pushed);
    if (pushed) await req('/api/sources/' + pushed.id, { method: 'DELETE' });
  }

  // 验收结束还原为「零内置源」的初始状态
  await req('/api/sources/demo-source', { method: 'DELETE' });
  const diskPath = path.join(__dirname, '..', 'data', 'sources.json');
  const disk = JSON.parse(fs.readFileSync(diskPath, 'utf8'));
  ck('数据文件为合法 JSON', Array.isArray(disk.sources));
  ck('无 .tmp 残留', !fs.existsSync(diskPath + '.tmp'));
  ck('测试数据已清理，源列表为空', disk.sources.length === 0, disk.sources.length + ' 条残留');
}

(async () => {
  console.log('== 数据层单元测试 ==');
  await unitTests();
  console.log(out.splice(0).join('\n'));
  const unitFailed = failed;
  console.log('\n== 原型端到端冒烟 ==');
  await e2eTests();
  console.log(out.splice(0).join('\n'));
  console.log('\n单元失败：' + unitFailed + '，端到端失败：' + (failed - unitFailed) + '，合计：' + failed);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
