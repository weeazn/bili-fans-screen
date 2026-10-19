// Transport and recovery checks without live Bilibili requests.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const html = fs.readFileSync(require('node:path').join(__dirname, '../index.html'), 'utf8');
function fn(name) {
  const start = html.search(new RegExp('(?:async )?function ' + name + '\\('));
  assert.ok(start >= 0, name);
  return html.slice(start, html.indexOf('\n}', start) + 2);
}
test('Android uses native same-origin endpoint and never public proxies', async () => {
  const urls = [];
  const ctx = vm.createContext({
    nativeScreen: true, pageServer: 'https://appassets.androidplatform.net',
    location: { protocol: 'https:' }, CFG: { server: '' },
    API_STAT: () => 'https://upstream/stat', API_CARD: () => 'https://upstream/card',
    PROXIES: [() => { throw new Error('Public proxy must not run'); }],
    fetchJSON: async url => { urls.push(url); return url.includes('/api/stat')
      ? { code: 0, data: { follower: 123, following: 4 } }
      : { code: 0, data: { card: { name: 'Test', face: '', sign: '' } } }; }
  });
  vm.runInContext(fn('parseStat') + '\n' + fn('fetchUp'), ctx);
  const result = await ctx.fetchUp('67368617');
  assert.equal(result.follower, 123);
  assert.equal(result.source, '手机直连');
  assert.equal(urls.length, 2);
  ctx.fetchJSON = async () => { throw new Error('Offline'); };
  await assert.rejects(() => ctx.fetchUp('67368617'), /Offline/);
});
test('Multi-account timer refreshes the account it switches to', () => {
  let heartbeat, refreshed;
  const ctx = vm.createContext({
    CFG: { demo: false, uids: ['1', '2'] },
    state: { busy: false, idx: 0, nextAt: 0, data: new Map([['1', {}], ['2', {}]]) },
    setInterval: fn => { heartbeat = fn; }, paintFooter() {}, paintAll() {},
    $: () => ({ classList: { remove() {}, add() {} }, offsetWidth: 0 })
  });
  ctx.currentUid = () => ctx.CFG.uids[ctx.state.idx];
  ctx.tick = () => { refreshed = ctx.currentUid(); };
  const start = html.indexOf('setInterval(() => {\n  if (CFG.demo) return;');
  assert.ok(start >= 0);
  vm.runInContext(html.slice(start, html.indexOf('}, 1000);', start) + 9), ctx);
  heartbeat();
  assert.equal(refreshed, '2');
});
test('Android screen switch is forwarded to the operating system', async () => {
  const calls = [];
  const ctx = vm.createContext({ nativeScreen: true, CFG: { wakeLock: true },
    wakeLock: null, videoFallback: null, window: { BiliScreen: { keepScreen: v => calls.push(v) } } });
  vm.runInContext(fn('acquireWake') + '\n' + fn('releaseWake'), ctx);
  await ctx.acquireWake();
  ctx.CFG.wakeLock = false;
  await ctx.acquireWake();
  assert.deepEqual(calls, [true, false]);
});
