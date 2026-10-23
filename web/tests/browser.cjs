'use strict';
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const test = require('node:test');
const source = readFileSync(process.env.NOQUI_TEST_APP_SOURCE || resolve(__dirname, '../static/app.js'), 'utf8');
const joinSource = readFileSync(resolve(__dirname, '../static/join.js'), 'utf8');
const UUID_A = '10000000-0000-4000-8000-000000000001';
const UUID_B = '10000000-0000-4000-8000-000000000002';
const flush = async () => { for (let i = 0; i < 60; i++) await Promise.resolve(); };
let clientSequence = 0;
function fixture(stored = new Map()) {
  const els = new Map(), documentEvents = new Map(), windowEvents = new Map(), intervals = [], timers = new Map(), streams = [], reports = [], requests = [];
  let clock = 1000, cookiePlayer = UUID_A, csrf = 'csrf-A', pendingState = null, denyCsrf = false, offline = false, expired = false, plays = 0;
  let playOverride = null;
  const storage = new Map(stored);
  function element() { return { textContent: '', hidden: false, disabled: false, attrs: {}, events: {}, classList: { toggle() {}, remove() {} }, querySelectorAll: () => [],
    addEventListener(type, fn) { this.events[type] = fn; }, setAttribute(k, v) { this.attrs[k] = v; }, removeAttribute() {}, replaceChildren() {}, closest: () => null }; }
  const get = id => { if (!els.has(id)) els.set(id, element()); return els.get(id); };
  const audio = get('audio');
  Object.assign(audio, { readyState: 4, paused: true, seeking: false, currentTime: 0, error: null,
    buffered: { length: 1, start: () => 0, end: () => 115.007 }, pause() { this.paused = true; }, load() {}, canPlayType: () => 'probably',
    play() { plays++; if (playOverride) return playOverride(); this.paused = false; return Promise.resolve(); } });
  let snapshot = state('PREPARING', '1');
  function state(phase, revision, player = cookiePlayer) {
    return { eventId: `epoch:${revision}`, serverTimeMs: clock, authorityLeaseExpiresAtMs: clock + 15000,
      me: { uuid: player, nickname: player === UUID_A ? 'PlayerA' : 'PlayerB', textureHash: null },
      room: { phase, players: [], roundNumber: 1, totalRounds: 0 },
      round: { roundId: 'round-one', revision, playbackGeneration: 'generation-one', number: 1, clipStartMs: 0, clipEndMs: 115000, anchorAtMs: clock, anchorPositionMs: 0, playing: phase === 'PLAYING' },
      media: { url: `/media/${'a'.repeat(64)}.ogg`, durationMs: 115007 }, reveal: null };
  }
  const store = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) };
  class SSE {
    static OPEN = 1;
    readyState = 1; events = {}; closed = false;
    constructor() { streams.push(this); }
    addEventListener(type, fn) { this.events[type] = fn; }
    close() { this.closed = true; this.readyState = 2; }
  }
  const document = { hidden: true, getElementById: get, createElement: element, createDocumentFragment: () => ({ append() {} }), addEventListener: (t, fn) => documentEvents.set(t, fn) };
  const context = { document, window: { addEventListener: (t, fn) => windowEvents.set(t, fn) }, navigator: { onLine: true },
    sessionStorage: store, localStorage: store, crypto: { randomUUID: () => `client-${++clientSequence}` }, performance: { now: () => clock },
    EventSource: SSE, setInterval: (fn, ms) => intervals.push({ fn, ms }),
    setTimeout(fn, ms) { const id = Symbol(); timers.set(id, { fn, ms }); return id; }, clearTimeout: id => timers.delete(id),
    fetch: async (path, options = {}) => {
      requests.push(path);
      if (expired && path.startsWith('/api/v1/')) return { ok: false, status: 401, json: async () => ({ error: { code: 'AUTH_REQUIRED' } }) };
      if (offline && ['/api/v1/state', '/api/v1/playback'].includes(path)) return { ok: false, status: 503, json: async () => ({ error: { code: 'PLUGIN_OFFLINE' } }) };
      if (path === '/api/v1/state' && pendingState) return pendingState;
      let data;
      if (path === '/api/v1/me') data = { me: { uuid: cookiePlayer, nickname: 'Player' }, csrfToken: csrf };
      else if (path === '/api/v1/time') data = { receivedAtMs: clock, sentAtMs: clock };
      else if (path === '/api/v1/state') data = snapshot;
      else if (path === '/api/v1/playback' || path === '/api/v1/auth/logout') {
        if (denyCsrf || options.headers['X-Noqui-CSRF'] !== csrf) return { ok: false, status: 403, json: async () => ({ error: { code: 'CSRF_REJECTED' } }) };
        if (path.endsWith('/logout')) return { ok: true, status: 204 };
        reports.push(JSON.parse(options.body)); data = { leaseExpiresAtMs: clock + 60000 };
      } else throw new Error(`Unexpected path ${path}`);
      return { ok: true, status: 200, json: async () => data };
    },
  };
  vm.runInNewContext(source.replace('  void boot();', '  globalThis.api = { boot, apply, align, paint, status, report, revoke };'), context);
  return { ...context.api, get, audio, streams, reports, requests, intervals, document, state, storage, plays: () => plays,
    setClock: value => { clock = value; }, setSnapshot: value => { snapshot = value; },
    switchPlayer: () => { cookiePlayer = UUID_B; csrf = 'csrf-B'; },
    denyCsrf: () => { denyCsrf = true; }, pendingState: promise => { pendingState = promise; },
    setOffline: value => { offline = value; }, expireSession: () => { expired = true; },
    setPlay: value => { playOverride = value; },
    event: async (type, data) => { streams.at(-1).events[type]({ data: JSON.stringify(data) }); await flush(); },
    storageEvent: async () => { windowEvents.get('storage')({ key: 'noqui-session-change', newValue: 'new-login' }); await flush(); },
    click: async id => { await get(id).events.click({ isTrusted: true }); await flush(); },
    gesture: async (type = 'click', overrides = {}) => { documentEvents.get(type)({ type, isTrusted: true, key: 'x', target: { closest: () => null }, ...overrides }); await flush(); },
    tick: async () => { intervals.find(x => x.ms === 250).fn(); await flush(); },
    disconnect: async () => { streams.at(-1).onerror(); await flush(); },
    reconnect: async () => { const [id, timer] = timers.entries().next().value; timers.delete(id); await timer.fn(); await flush(); },
  };
}
test('new round revision reports PREPARED immediately without a heartbeat timer', async () => {
  const r = fixture(); await r.boot(); await flush();
  assert.equal(r.reports.at(-1).status, 'PREPARED');
  r.apply(r.state('PREPARING', '2')); await flush();
  assert.equal(r.reports.at(-1).roundRevision, '2');
  assert.equal(r.reports.at(-1).status, 'PREPARED');
});
test('background SSE renews browser telemetry without interval callbacks', async () => {
  const r = fixture(); await r.boot(); await flush(); const count = r.reports.length;
  for (let time = 6000; time <= 76000; time += 5000) {
    r.setClock(time); await r.event('lease', { serverTimeMs: time, authorityLeaseExpiresAtMs: time + 15000, pluginOnline: true });
  }
  assert(r.reports.length > count + 3); assert(r.reports.length < count + 7, 'no heartbeat storm');
});
test('healthy authority is honored until its actual expiry; an OPEN silent SSE polls in background', async () => {
  const r = fixture(); await r.boot(); await flush();
  r.setClock(14000); r.paint(); assert.equal(r.get('connection-text').textContent, '연결됨');
  r.setSnapshot(r.state('PREPARING', '2')); await r.tick();
  assert(r.requests.filter(p => p === '/api/v1/state').length >= 2);
  r.setClock(50000); r.align(); r.paint(); assert.equal(r.audio.paused, true); assert.equal(r.get('connection-text').textContent, '연결 대기');
});
test('two-player cookie switch stops old CSRF tab and cannot log out or adopt the newer player', async () => {
  const r = fixture(); await r.boot(); await flush(); r.switchPlayer(); r.setClock(17000); await r.report(); await flush();
  assert.equal(r.get('connection-text').textContent, '다른 연결'); assert.equal(r.get('logout').disabled, true); assert(r.streams.every(s => s.closed));
  const count = r.requests.filter(p => p.endsWith('/logout')).length; await r.click('logout');
  assert.equal(r.requests.filter(p => p.endsWith('/logout')).length, count);
  await r.boot(); await flush(); assert.equal(r.get('connection-text').textContent, '다른 연결');
});
test('storage notification immediately retires old tab and ignores its queued SSE event', async () => {
  const r = fixture(); await r.boot(); await flush(); const stream = r.streams.at(-1);
  await r.storageEvent(); stream.events.state({ data: JSON.stringify(r.state('PLAYING', '2')) }); await flush();
  await r.tick();
  assert.equal(r.get('connection-text').textContent, '다른 연결'); assert.equal(r.plays(), 0);
});
test('in-flight refreshed state cannot resurrect a revoked tab', async () => {
  const r = fixture(); await r.boot(); await flush(); let resolveState;
  r.pendingState(new Promise(resolve => { resolveState = resolve; })); r.setClock(14000); await r.tick();
  r.revoke('SESSION_CHANGED'); resolveState({ ok: true, status: 200, json: async () => r.state('PLAYING', '2') }); await flush();
  assert.equal(r.get('connection-text').textContent, '다른 연결'); assert.equal(r.plays(), 0);
});
test('duplicate PREPARED SSE frames do not trigger report loops; documents use distinct client IDs', async () => {
  const a = fixture(); await a.boot(); await flush(); const b = fixture(a.storage); await b.boot(); await flush();
  assert.notEqual(a.reports[0].clientId, b.reports[0].clientId);
  const count = a.reports.length;
  for (let i = 0; i < 20; i++) { a.apply(a.state('PREPARING', '1')); await flush(); }
  assert.equal(a.reports.length, count);
});
test('temporary SSE loss reconnects with the same session and leaves healthy audio playing', async () => {
  const r = fixture(); await r.boot(); await flush();
  const playing = r.state('PLAYING', '2'); r.apply(playing); await flush();
  assert.equal(r.audio.paused, false);
  const plays = r.plays(); const meRequests = r.requests.filter(path => path === '/api/v1/me').length;
  r.setClock(6000); r.audio.currentTime = 5; await r.disconnect();
  assert.equal(r.audio.paused, false);
  r.setSnapshot({ ...playing, serverTimeMs: 6000, authorityLeaseExpiresAtMs: 21000 }); await r.reconnect();
  assert.equal(r.streams.length, 2); assert.equal(r.streams[0].closed, true); assert.equal(r.streams[1].closed, false);
  assert.equal(r.audio.paused, false); assert.equal(r.plays(), plays);
  assert.equal(r.requests.filter(path => path === '/api/v1/me').length, meRequests);
  assert.equal(r.get('connection-text').textContent, '연결됨');
});
test('a scheduled stream reconnect cannot reopen a replaced identity', async () => {
  const r = fixture(); await r.boot(); await flush(); await r.disconnect();
  r.revoke('SESSION_CHANGED'); await r.tick();
  assert.equal(r.streams.length, 1); assert(r.streams.every(stream => stream.closed));
  assert.equal(r.get('connection-text').textContent, '다른 연결');
});
test('preparation phases and buffered media show concise idle text while failures remain visible', async () => {
  const r = fixture(); await r.boot(); await flush();
  for (const [phase, revision] of [['WAITING_FOR_MEDIA', '2'], ['PREPARING', '3'], ['COUNTDOWN', '4']]) {
    r.apply(r.state(phase, revision)); await flush();
    for (const id of ['phase-label', 'audio-status', 'message']) assert(!/준비/.test(r.get(id).textContent));
    assert.equal(r.get('message').textContent, '');
  }
  r.audio.error = { code: 3 }; r.paint(); assert.equal(r.get('audio-status').textContent, '오디오 오류');
});
test('authority gap pauses safely and resumes the same authenticated tab after a fresh snapshot', async () => {
  const r = fixture(); await r.boot(); await flush(); r.apply(r.state('PLAYING', '2')); await flush();
  assert.equal(r.audio.paused, false);
  r.setClock(17000); r.setOffline(true); await r.event('offline', { code: 'PLUGIN_OFFLINE' }); await r.tick();
  assert.equal(r.audio.paused, true); assert.equal(r.get('connection-text').textContent, '연결 대기');
  assert.equal(r.get('logout').disabled, false); assert.equal(r.streams[0].closed, false);
  assert.equal(r.get('message').textContent, '게임 연결을 기다리는 중');
  r.setClock(80000); r.setOffline(false); await r.event('state', r.state('PLAYING', '3'));
  assert.equal(r.audio.paused, false); assert.equal(r.get('connection-text').textContent, '연결됨');
  assert.equal(r.get('message').textContent, '');
  assert.equal(r.requests.filter(path => path === '/api/v1/me').length, 1, 'no new login or CSRF adoption');
});
test('an expired or revoked session cannot reconnect itself after a temporary stream failure', async () => {
  const r = fixture(); await r.boot(); await flush(); await r.disconnect();
  r.expireSession(); await r.reconnect(); await r.tick();
  assert.equal(r.streams.length, 1); assert.equal(r.audio.paused, true);
  assert.equal(r.get('connection-text').textContent, '연결 끊김'); assert.equal(r.get('logout').disabled, true);
});

test('first playback click while media loads stays a play request and starts when ready', async () => {
  const r = fixture(); r.audio.readyState = 1;
  r.setSnapshot(r.state('PLAYING', '1')); await r.boot(); await flush();
  assert.equal(r.plays(), 0); assert.equal(r.get('playback').textContent, '재생');
  await r.click('playback');
  assert.notEqual(r.status(), 'PAUSED'); assert.equal(r.plays(), 0);
  r.audio.readyState = 4; r.audio.events.canplay(); await flush();
  assert.equal(r.status(), 'PLAYING'); assert.equal(r.get('playback').textContent, '일시 정지');
  await r.click('playback'); await r.gesture();
  assert.equal(r.audio.paused, true); assert.equal(r.status(), 'PAUSED');
});

test('a genuine page gesture arriving before a stale autoplay denial gets one fresh attempt', async () => {
  const r = fixture(); let rejectFirst;
  r.setPlay(() => new Promise((_, reject) => { rejectFirst = reject; }));
  r.setSnapshot(r.state('PLAYING', '1')); await r.boot(); await flush();
  assert.equal(r.plays(), 1);
  await r.gesture('pointerup'); assert.equal(r.plays(), 1);
  r.setPlay(null); rejectFirst(Object.assign(new Error('policy'), { name: 'NotAllowedError' })); await flush();
  assert.equal(r.plays(), 2); assert.equal(r.status(), 'PLAYING');
});

test('a fresh denial after a page gesture stays blocked without repeated autoplay attempts', async () => {
  const r = fixture(); let rejectFirst;
  r.setPlay(() => new Promise((_, reject) => { rejectFirst = reject; }));
  r.setSnapshot(r.state('PLAYING', '1')); await r.boot(); await flush();
  await r.gesture('keydown');
  r.setPlay(() => Promise.reject(Object.assign(new Error('policy'), { name: 'NotAllowedError' })));
  rejectFirst(Object.assign(new Error('policy'), { name: 'NotAllowedError' })); await flush();
  assert.equal(r.plays(), 2); assert.equal(r.status(), 'NEEDS_GESTURE');
  for (let i = 0; i < 10; i++) r.align();
  await flush(); assert.equal(r.plays(), 2); assert.equal(r.get('playback').textContent, '재생');
});

test('direct arrival truthfully waits for a trusted page gesture without changing stored mute', async () => {
  const r = fixture(new Map([['noqui-volume', '0']]));
  r.setPlay(() => Promise.reject(Object.assign(new Error('policy'), { name: 'NotAllowedError' })));
  r.setSnapshot(r.state('PLAYING', '1')); await r.boot(); await flush();
  assert.equal(r.status(), 'NEEDS_GESTURE'); assert.equal(r.get('audio-status').textContent, '재생 대기');
  assert.equal(r.get('playback').textContent, '재생'); assert.equal(r.audio.muted, true);
  r.setPlay(null); await r.gesture('click', { isTrusted: false });
  assert.equal(r.plays(), 1);
  await r.gesture('keydown', { key: 'Shift' }); assert.equal(r.plays(), 1);
  await r.gesture('click', { target: { closest: selector => selector.includes('#reconnect') } });
  assert.equal(r.status(), 'PLAYING'); assert.equal(r.audio.muted, true); assert.equal(r.audio.volume, 0);
});

test('late autoplay denial cannot resume explicit pause or a revoked session after a gesture', async () => {
  for (const action of ['pause', 'revoke']) {
    const r = fixture(); let rejectFirst;
    r.setPlay(() => new Promise((_, reject) => { rejectFirst = reject; }));
    r.setSnapshot(r.state('PLAYING', '1')); await r.boot(); await flush(); await r.gesture();
    if (action === 'pause') await r.click('playback'); else r.revoke('SESSION_REVOKED');
    r.setPlay(null); rejectFirst(Object.assign(new Error('policy'), { name: 'NotAllowedError' })); await flush();
    assert.equal(r.plays(), 1); assert.equal(r.audio.paused, true);
  }
});

async function joinFixture(hash, response) {
  const elements = new Map(), storage = new Map(), requests = [], navigations = [], historyPaths = [];
  const get = id => { if (!elements.has(id)) elements.set(id, { textContent: '', hidden: true }); return elements.get(id); };
  const store = { setItem: (key, value) => storage.set(key, value) };
  vm.runInNewContext(joinSource, {
    URLSearchParams, location: { hash, replace: path => navigations.push(path) }, history: { replaceState: (_, __, path) => historyPaths.push(path) },
    document: { getElementById: get }, sessionStorage: store, localStorage: store, crypto: { randomUUID: () => 'login-change-marker' },
    fetch: async (path, options) => { requests.push({ path, options }); if (response instanceof Error) throw response; return response; },
  });
  await flush(); return { get, storage, requests, navigations, historyPaths };
}

test('join redeems once with no extra consent button and preserves identity-change notification', async () => {
  const r = await joinFixture('#t=' + 'a'.repeat(43), { ok: true, json: async () => ({ me: { uuid: UUID_A }, csrfToken: 'csrf-A' }) });
  assert.deepEqual(r.historyPaths, ['/join']); assert.deepEqual(r.navigations, ['/']);
  assert.equal(r.requests.length, 1); assert.equal(r.requests[0].options.credentials, 'same-origin');
  assert.equal(r.storage.get('noqui-player'), UUID_A); assert.equal(r.storage.get('noqui-session-change'), 'login-change-marker');
  for (const name of ['index.html', 'join.html']) {
    const html = readFileSync(resolve(__dirname, '../static/' + name), 'utf8');
    assert(!html.includes('id="enable"') && !html.includes('소리 켜기'));
  }
});

test('expired, malformed and failed joins never navigate or adopt another identity', async () => {
  for (const [hash, response, requests] of [
    ['#t=invalid', { ok: true }, 0],
    ['#t=' + 'a'.repeat(43), { ok: false, status: 401 }, 1],
    ['#t=' + 'a'.repeat(43), new Error('network'), 1],
  ]) {
    const r = await joinFixture(hash, response);
    assert.equal(r.requests.length, requests); assert.equal(r.navigations.length, 0); assert.equal(r.storage.size, 0);
    assert.equal(r.get('join-status').textContent, '연결이 만료되었습니다'); assert.equal(r.get('join-back').hidden, false);
  }
});

test('double speed uses source positions and wall progress through pause, countdown, resume and end', async () => {
  const r = fixture();
  const fast = (phase, revision, position = 0, anchor = 1000) => {
    const next = r.state(phase, revision);
    Object.assign(next.round, { playbackRate: 2, anchorPositionMs: position, anchorAtMs: anchor, playing: ['PLAYING', 'COUNTDOWN'].includes(phase) });
    return next;
  };
  r.setSnapshot(fast('PLAYING', '1')); await r.boot(); await flush();
  assert.equal(r.audio.playbackRate, 2); assert.equal(r.audio.defaultPlaybackRate, 2); assert.equal(r.audio.preservesPitch, false);
  r.setClock(6000); r.apply(fast('PLAYING', '2')); await flush();
  assert.equal(r.audio.currentTime, 10); assert.equal(r.get('progress').value, 5000); assert.equal(r.get('progress').max, 57500);
  assert.equal(r.get('elapsed').textContent, '0:05'); assert.equal(r.get('remaining').textContent, '0:52');
  assert.equal(r.reports.at(-1).positionMs, 10000, 'telemetry remains source media milliseconds');
  r.apply(fast('PAUSED', '3', 10000, 6000)); await flush();
  r.setClock(9000); r.align(); r.paint(); assert.equal(r.audio.currentTime, 10); assert.equal(r.audio.paused, true);
  r.apply(fast('COUNTDOWN', '4', 10000, 12000)); await flush();
  r.setClock(11000); r.align(); r.paint(); assert.equal(r.audio.currentTime, 10); assert.equal(r.audio.paused, true); assert.equal(r.get('countdown').textContent, '1');
  r.setClock(14000); r.align(); r.paint(); await flush();
  assert.equal(r.audio.currentTime, 14); assert.equal(r.audio.paused, false); assert.equal(r.get('progress').value, 7000);
  r.setClock(64500); r.apply(fast('PLAYING', '5', 10000, 12000)); await flush();
  assert.equal(r.audio.currentTime, 115); assert.equal(r.audio.paused, true);
  assert.equal(r.get('progress').value, 57500); assert.equal(r.get('remaining').textContent, '0:00');
  const normal = r.state('PLAYING', '6'); normal.round.roundId = 'round-two'; normal.round.playbackGeneration = 'generation-two';
  r.apply(normal); await flush();
  assert.equal(r.audio.playbackRate, 1); assert.equal(r.audio.defaultPlaybackRate, 1); assert.equal(r.audio.preservesPitch, true);
  assert.equal(r.get('progress').max, 115000);
});

test('double-speed preparation needs half a wall second buffered in source time and caps at clip end', async () => {
  const r = fixture(); const next = r.state('PREPARING', '1'); next.round.playbackRate = 2;
  r.audio.buffered.end = () => .75; r.setSnapshot(next); await r.boot(); await flush();
  assert.equal(r.status(), 'LOADING');
  r.audio.buffered.end = () => 1; r.audio.events.canplay(); await flush();
  assert.equal(r.status(), 'PREPARED'); assert.equal(r.reports.at(-1).status, 'PREPARED');
  const ending = r.state('PREPARING', '2'); Object.assign(ending.round, { playbackRate: 2, anchorPositionMs: 114800 });
  r.setClock(4000); r.audio.buffered.end = () => 115; r.apply(ending); await flush();
  assert.equal(r.audio.currentTime, 114.8); assert.equal(r.status(), 'PREPARED');
});
