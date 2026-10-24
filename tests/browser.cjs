'use strict';
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const test = require('node:test');
const source = readFileSync(process.env.NOQUI_TEST_APP_SOURCE || resolve(__dirname, '../static/app.js'), 'utf8');
const joinSource = readFileSync(process.env.NOQUI_TEST_JOIN_SOURCE || resolve(__dirname, '../static/join.js'), 'utf8');
const UUID_A = '10000000-0000-4000-8000-000000000001';
const UUID_B = '10000000-0000-4000-8000-000000000002';
const flush = async () => { for (let i = 0; i < 60; i++) await Promise.resolve(); };
let clientSequence = 0;
function fixture(stored = new Map()) {
  const els = new Map(), documentEvents = new Map(), windowEvents = new Map(), intervals = [], timers = new Map(), streams = [], reports = [], requests = [];
  let clock = 1000, cookiePlayer = UUID_A, csrf = 'csrf-A', pendingState = null, denyCsrf = false, offline = false, expired = false, plays = 0;
  let playOverride = null, checks = 0;
  const storage = new Map(stored);
  function element() { return { textContent: '', hidden: false, disabled: false, attrs: {}, events: {}, classList: { toggle() {}, remove() {} }, querySelectorAll: () => [],
    addEventListener(type, fn) { this.events[type] = fn; }, setAttribute(k, v) { this.attrs[k] = v; }, removeAttribute() {}, replaceChildren() {}, closest: () => null }; }
  const get = id => { if (!els.has(id)) els.set(id, element()); return els.get(id); };
  const audio = get('audio');
  Object.assign(audio, { readyState: 4, paused: true, seeking: false, currentTime: 0, error: null,
    buffered: { length: 1, start: () => 0, end: () => 115.007 }, pause() { this.paused = true; }, load() {}, canPlayType: () => 'probably',
    play() { if (this.src === '/playback-check.wav') checks++; else plays++; if (playOverride) return playOverride(); this.paused = false; return Promise.resolve(); } });
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
  return { ...context.api, get, audio, streams, reports, requests, intervals, document, state, storage, plays: () => plays, checks: () => checks,
    setClock: value => { clock = value; }, setSnapshot: value => { snapshot = value; },
    switchPlayer: () => { cookiePlayer = UUID_B; csrf = 'csrf-B'; },
    denyCsrf: () => { denyCsrf = true; }, pendingState: promise => { pendingState = promise; },
    setOffline: value => { offline = value; }, expireSession: () => { expired = true; },
    setPlay: value => { playOverride = value; },
    advancePlayback: async (ms = 250) => { clock += ms; if (!audio.paused && !audio.seeking) audio.currentTime += ms / 1000 * audio.playbackRate; audio.events.timeupdate?.(); intervals.find(x => x.ms === 250).fn(); await flush(); },
    event: async (type, data) => { streams.at(-1).events[type]({ data: JSON.stringify(data) }); await flush(); },
    storageEvent: async () => { windowEvents.get('storage')({ key: 'noqui-session-change', newValue: 'new-login' }); await flush(); },
    click: async id => { await get(id).events.click({ isTrusted: true }); await flush(); },
    gesture: async (type = 'click', overrides = {}) => { documentEvents.get(type)({ type, isTrusted: true, key: 'x', target: { closest: () => null }, ...overrides }); await flush(); },
    tick: async () => { intervals.find(x => x.ms === 250).fn(); await flush(); },
    disconnect: async () => { streams.at(-1).onerror(); await flush(); },
    reconnect: async () => { const [id, timer] = timers.entries().next().value; timers.delete(id); await timer.fn(); await flush(); },
  };
}
test('new round revision reports PREPARED after the output check without a heartbeat timer', async () => {
  const r = fixture(); await r.boot(); await flush();
  assert.equal(r.reports.at(-1).status, 'PREPARED');
  r.apply(r.state('PREPARING', '2')); await flush();
  assert.equal(r.reports.at(-1).roundRevision, '2');
  assert.equal(r.reports.at(-1).status, 'PREPARED');
  assert.equal(r.checks(), 1); assert.equal(r.plays(), 0, 'the quiz track is not exposed during the check');
});

test('buffered preparation denied audible autoplay never reports readiness or a countdown', async () => {
  const r = fixture(new Map([['noqui-volume', '0']]));
  r.setPlay(() => { assert.equal(r.audio.src, '/playback-check.wav'); assert.equal(r.audio.muted, false); assert(r.audio.volume > 0); return Promise.reject(Object.assign(new Error('policy'), { name: 'NotAllowedError' })); });
  await r.boot(); await flush();
  assert.equal(r.status(), 'NEEDS_GESTURE'); assert.equal(r.reports.at(-1).status, 'NEEDS_GESTURE');
  assert(!r.reports.some(report => ['PREPARED', 'PLAYING'].includes(report.status)));
  assert.equal(r.audio.muted, true); assert.equal(r.audio.volume, 0); assert.equal(r.get('countdown').textContent, '');
  for (let i = 0; i < 10; i++) await r.tick(); assert.equal(r.checks(), 1, 'denials do not create a retry storm');
  const countdown = r.state('COUNTDOWN', '2'); Object.assign(countdown.round, { playing: true, anchorAtMs: 3000 });
  r.apply(countdown); await flush(); assert.equal(r.get('countdown').textContent, '');
});

test('a policy change can recover automatically on a background lease with zero gestures', async () => {
  const r = fixture(); r.setPlay(() => Promise.reject(Object.assign(new Error('policy'), { name: 'NotAllowedError' })));
  await r.boot(); await flush(); assert.equal(r.status(), 'NEEDS_GESTURE');
  r.setPlay(null); r.setClock(16000); await r.event('lease', { authorityLeaseExpiresAtMs: 31000 });
  assert.equal(r.status(), 'PREPARED'); assert.equal(r.checks(), 2); assert.equal(r.plays(), 0); assert.equal(r.document.hidden, true);
});

test('pending output checks cannot report the probe position, grant readiness after revocation or override user pause', async () => {
  for (const action of ['revoke', 'pause']) {
    const r = fixture(); let finish;
    r.setPlay(() => { r.audio.paused = false; return new Promise(resolve => { finish = resolve; }); });
    await r.boot(); await flush(); assert.equal(r.status(), 'BUFFERING');
    assert.equal(r.reports.at(-1).positionMs, null); assert.equal(r.reports.at(-1).bufferedUntilMs, null);
    if (action === 'revoke') r.revoke('SESSION_REVOKED');
    else await r.click('playback');
    finish(); await flush();
    assert(!r.reports.some(report => ['PREPARED', 'PLAYING'].includes(report.status))); assert.equal(r.audio.paused, true);
  }
});

test('an output check from an old round cannot prepare a replacement source', async () => {
  const r = fixture(), pending = [];
  r.setPlay(() => { r.audio.paused = false; return new Promise(resolve => pending.push(resolve)); });
  await r.boot(); await flush();
  const next = r.state('PREPARING', '2'); next.round.roundId = 'round-two'; next.media.url = `/media/${'b'.repeat(64)}.ogg`;
  r.apply(next); await flush(); assert.equal(pending.length, 2);
  pending[0](); await flush(); assert.equal(r.status(), 'BUFFERING');
  pending[1](); await flush(); assert.equal(r.status(), 'PREPARED'); assert.equal(r.reports.at(-1).roundId, 'round-two');
});

test('round label uses session registered songs including played songs independently of the round limit', async () => {
  const r = fixture(); await r.boot(); await flush();
  const cases = [
    ['1', 'PREPARING', 1, 3, 200, '1/3'],
    ['2', 'PREPARING', 1, 3, 0, '1/3'],
    ['3', 'PREPARING', 1, 2, 0, '1/2'],
    ['4', 'PLAYING', 2, 3, 100, '2/3'],
    ['5', 'ENDED', 2, 2, 0, '2/2'],
    ['6', 'IDLE', 0, 0, 100, '0/0'],
  ];
  for (const [revision, phase, number, registered, limit, label] of cases) {
    const next = r.state(phase, revision);
    Object.assign(next.room, { roundNumber: number, registeredSongs: registered, totalRounds: limit });
    if (phase === 'IDLE' || phase === 'ENDED') { next.round = null; next.media = null; }
    else next.round.number = number;
    r.apply(next); await flush();
    assert.equal(r.get('round-label').textContent, label);
    assert(!/[·∞]/.test(r.get('round-label').textContent));
  }
});

test('missing or invalid registered count falls back without inventing a denominator', async () => {
  const r = fixture(); await r.boot(); await flush(); let revision = 1;
  for (const registered of [undefined, null, -1, 1.5, '3', NaN, Infinity, 4294967296]) {
    const next = r.state('PREPARING', String(revision++));
    Object.assign(next.room, { roundNumber: 1, registeredSongs: registered, totalRounds: 200 });
    r.apply(next); await flush(); assert.equal(r.get('round-label').textContent, '1 라운드');
  }
  const legacy = r.state('IDLE', String(revision++)); legacy.round = null; legacy.media = null;
  delete legacy.room.roundNumber; delete legacy.room.totalRounds;
  r.apply(legacy); await flush(); assert.equal(r.get('round-label').textContent, '대기실');
});

test('registered-count changes preserve naturally playing audio, position, speed and mute', async () => {
  const r = fixture(new Map([['noqui-volume', '0']]));
  const playing = r.state('PLAYING', '1'); playing.round.playbackRate = 2; playing.room.registeredSongs = 2;
  r.setSnapshot(playing); await r.boot(); await flush(); await r.advancePlayback();
  assert.equal(r.status(), 'PLAYING'); const plays = r.plays(), position = r.audio.currentTime, src = r.audio.src;
  r.apply({ ...playing, eventId: 'epoch:2', room: { ...playing.room, registeredSongs: 3, totalRounds: 100 } }); await flush();
  assert.equal(r.get('round-label').textContent, '1/3'); assert.equal(r.status(), 'PLAYING');
  assert.equal(r.plays(), plays); assert.equal(r.audio.currentTime, position); assert.equal(r.audio.src, src);
  assert.equal(r.audio.playbackRate, 2); assert.equal(r.audio.muted, true);
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
  await r.advancePlayback();
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
  await r.advancePlayback();
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
  assert.equal(r.status(), 'NEEDS_GESTURE'); assert.equal(r.get('audio-status').textContent, '자동 재생 차단됨');
  assert.equal(r.get('playback').textContent, '재생'); assert.equal(r.audio.muted, true);
  r.setPlay(null); await r.gesture('click', { isTrusted: false });
  assert.equal(r.plays(), 1);
  await r.gesture('keydown', { key: 'Shift' }); assert.equal(r.plays(), 1);
  await r.gesture('click', { target: { closest: selector => selector.includes('#reconnect') } });
  await r.advancePlayback();
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

async function joinFixture(hash, response, pathname = '/join', reducedMotion = false) {
  const elements = new Map(), storage = new Map(), requests = [], navigations = [], historyPaths = [], scripts = [], timers = [];
  let play = () => Promise.resolve(), plays = 0;
  const get = id => {
    if (!elements.has(id)) elements.set(id, { textContent: '', hidden: id === 'player-screen', disabled: id === 'connect-button',
      handlers: {}, classes: new Set(), addEventListener(type, fn) { this.handlers[type] = fn; },
      classList: { add: name => get(id).classes.add(name), remove: name => get(id).classes.delete(name) },
      play() { plays++; return play(); }, pause() { this.paused = true; }, load() {}, focus() { this.focused = true; } });
    return elements.get(id);
  };
  const store = { setItem: (key, value) => storage.set(key, value), getItem: key => storage.get(key) || null };
  vm.runInNewContext(joinSource, {
    URLSearchParams, location: { pathname, hash, replace: path => navigations.push(path) }, history: { replaceState: (_, __, path) => historyPaths.push(path) },
    document: { getElementById: get, createElement: () => ({ remove() { this.removed = true; } }), head: { append: script => scripts.push(script) } }, sessionStorage: store, localStorage: store, crypto: { randomUUID: () => 'login-change-marker' },
    matchMedia: () => ({ matches: reducedMotion }), setTimeout: fn => timers.push(fn),
    fetch: async (path, options) => { requests.push({ path, options }); if (response instanceof Error) throw response; return response; },
  });
  await flush(); return { get, storage, requests, navigations, historyPaths, scripts, timers,
    click: () => get('connect-button').handlers.click(), plays: () => plays, setPlay: fn => { play = fn; } };
}

test('join redeems in the same document and keeps its audio element and interaction history', async () => {
  const r = await joinFixture('#t=' + 'a'.repeat(43), { ok: true, json: async () => ({ me: { uuid: UUID_A }, csrfToken: 'csrf-A' }) });
  assert.deepEqual(r.historyPaths, ['/join', '/']); assert.deepEqual(r.navigations, []);
  assert.equal(r.scripts.length, 0); assert.equal(r.plays(), 0); assert.equal(r.get('connect-button').disabled, false);
  assert.equal(r.requests.length, 1); assert.equal(r.requests[0].options.credentials, 'same-origin');
  assert.equal(r.storage.get('noqui-player'), UUID_A); assert.equal(r.storage.get('noqui-session-change'), 'login-change-marker');
  const html = readFileSync(resolve(__dirname, '../static/index.html'), 'utf8');
  assert(!html.includes('id="enable"') && !html.includes('소리 켜기'));
  assert(html.includes('연결하기') && html.includes('id="player-screen" class="shell" hidden'));
  assert(html.includes('src="/join.js"')); assert(!html.includes('src="/app.js"'), 'player starts only after join redemption');
  const audio = r.get('audio'); r.click();
  assert.equal(r.plays(), 1, 'audio is unlocked synchronously inside the connection click');
  assert.equal(audio.src, '/playback-check.wav'); assert.equal(audio.muted, false); assert.equal(audio.volume, .65);
  r.click(); await flush(); assert.equal(r.plays(), 1); assert.equal(r.scripts.length, 1);
  assert.equal(r.get('player-screen').hidden, true);
  r.scripts[0].onload();
  assert.equal(r.get('player-screen').hidden, false); assert.equal(r.get('connect-screen').hidden, false);
  assert(r.get('connect-screen').classes.has('is-leaving')); assert(r.get('player-screen').classes.has('is-entering'));
  r.timers[0](); assert.equal(r.get('connect-screen').hidden, true); assert(r.get('track-title').focused);
  assert.equal(r.get('audio'), audio); assert.equal(r.navigations.length, 0);
});

test('expired, malformed and failed joins never navigate or adopt another identity', async () => {
  for (const [hash, response, requests] of [
    ['#t=invalid', { ok: true }, 0],
    ['#t=' + 'a'.repeat(43), { ok: false, status: 401 }, 1],
    ['#t=' + 'a'.repeat(43), new Error('network'), 1],
  ]) {
    const r = await joinFixture(hash, response);
    assert.equal(r.requests.length, requests); assert.equal(r.navigations.length, 0); assert.equal(r.storage.size, 0);
    assert.equal(r.scripts.length, 0, 'failed redemption must not boot an older cookie identity');
    assert.equal(r.get('connect-button').disabled, true); assert.equal(r.get('player-screen').hidden, true);
    r.click(); assert.equal(r.plays(), 0);
  }
});

test('join waits for redemption and home arrivals also wait for connection click', async () => {
  let resolveJoin;
  const r = await joinFixture('#t=' + 'a'.repeat(43), new Promise(resolve => { resolveJoin = resolve; }));
  const audio = r.get('audio');
  assert.equal(r.scripts.length, 0); assert.deepEqual(r.historyPaths, ['/join']);
  resolveJoin({ ok: true, json: async () => ({ me: { uuid: UUID_A } }) }); await flush();
  assert.equal(r.get('audio'), audio); assert.equal(r.scripts.length, 0); assert.equal(r.navigations.length, 0);
  const home = await joinFixture('', { ok: true, json: async () => ({ me: { uuid: UUID_A } }) }, '/');
  assert.equal(home.requests.length, 1); assert.equal(home.requests[0].path, '/api/v1/me'); assert.deepEqual(home.historyPaths, []);
  assert.equal(home.scripts.length, 0); home.click(); await flush();
  assert.deepEqual(home.scripts.map(script => script.src), ['/app.js']);
});

test('failed audio unlock and script load allow retry without exposing the player', async () => {
  const r = await joinFixture('', { ok: true, json: async () => ({ me: { uuid: UUID_A } }) }, '/');
  r.setPlay(() => Promise.reject(Object.assign(new Error('blocked'), { name: 'NotAllowedError' })));
  r.click(); await flush(); assert.equal(r.scripts.length, 0); assert.equal(r.get('connect-button').disabled, false);
  assert.equal(r.get('player-screen').hidden, true);
  r.setPlay(() => Promise.resolve()); r.click(); await flush(); r.scripts[0].onerror();
  assert.equal(r.get('connect-button').disabled, false); assert.equal(r.get('player-screen').hidden, true);
  assert(r.scripts[0].removed); r.click(); await flush(); assert.equal(r.scripts.length, 2);
});

test('reduced motion completes the connection without animation delay', async () => {
  const r = await joinFixture('', { ok: true, json: async () => ({ me: { uuid: UUID_A } }) }, '/', true);
  r.click(); await flush(); r.scripts[0].onload();
  assert.equal(r.get('connect-screen').hidden, true); assert.equal(r.get('player-screen').hidden, false); assert.equal(r.timers.length, 0);
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
  assert.equal(r.audio.currentTime, 10); assert.equal(r.audio.paused, false); assert.equal(r.get('progress').value, 5000);
  await r.advancePlayback();
  r.apply(fast('PLAYING', '5', 10500, 14250)); await flush();
  assert.equal(r.audio.currentTime, 10.5); assert.equal(r.get('progress').value, 5250);
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

test('two new sources finish slow startup seeks before correcting the advancing clock again', async () => {
  const r = fixture();
  let position = 0, seeks = 0;
  Object.defineProperty(r.audio, 'currentTime', {
    get: () => position,
    set: value => { position = value; seeks++; r.audio.seeking = true; r.audio.readyState = 1; },
  });
  r.audio.load = () => {
    position = 0; r.audio.readyState = 0; r.audio.seeking = false; r.audio.paused = true;
    r.audio.buffered = { length: 0 };
  };
  const idle = r.state('WAITING', '1'); idle.round = null; idle.media = null;
  r.setSnapshot(idle); await r.boot(); await flush();
  await r.gesture('pointerup'); assert.equal(r.plays(), 0, 'idle interaction must not manufacture media playback');
  for (let song = 1; song <= 2; song++) {
    const start = song * 10000;
    r.setClock(start);
    const next = r.state('PLAYING', String(song + 1));
    next.round.roundId = `round-${song}`; next.round.playbackGeneration = `generation-${song}`;
    next.media.url = `/media/${String(song).repeat(64)}.ogg`;
    r.apply(next); await flush();
    assert.equal(r.audio.readyState, 0); assert.notEqual(r.status(), 'PLAYING');
    r.setClock(start + 1000); r.audio.readyState = 1; r.audio.events.loadedmetadata(); await flush();
    assert.equal(r.audio.seeking, true);
    const initialSeeks = seeks;
    // A real seek may need multiple range responses and exceed the correction interval.
    r.setClock(start + 4000); r.audio.readyState = 4; r.audio.seeking = false;
    r.audio.buffered = { length: 1, start: () => 0, end: () => 115.007 };
    r.audio.events.seeked(); await flush();
    assert.equal(seeks, initialSeeks, 'seek completion must get a chance to start playback');
    assert.equal(r.audio.paused, false); assert.equal(r.status(), 'BUFFERING');
    position += .25; r.setClock(start + 4250); await r.tick();
    assert.equal(seeks, initialSeeks); assert.equal(r.audio.currentTime, 1.25, 'media clock advances independently of sync seeks');
    assert.equal(r.status(), 'PLAYING');
    r.setClock(start + 6250); await r.tick();
    assert.equal(seeks, initialSeeks + 1, 'drift correction resumes after startup settles');
  }
});

test('an unresolved play request cannot be labelled PLAYING or repeatedly seeked', async () => {
  const r = fixture(); let finishPlay;
  r.setPlay(() => { r.audio.paused = false; return new Promise(resolve => { finishPlay = resolve; }); });
  const next = r.state('COUNTDOWN', '1'); next.round.playing = true;
  r.setSnapshot(next); await r.boot(); await flush();
  assert.equal(r.audio.paused, false); assert.equal(r.audio.readyState, 4);
  assert.equal(r.status(), 'BUFFERING'); assert.equal(r.get('audio-status').textContent, '버퍼링 중');
  r.setClock(4000); await r.tick();
  assert.equal(r.audio.currentTime, 0, 'pending playback must not be interrupted by correction seeks');
  finishPlay(); await flush();
  assert.equal(r.status(), 'BUFFERING', 'a fulfilled play promise alone is not playback progress');
  await r.advancePlayback();
  assert.equal(r.status(), 'PLAYING');
  r.audio.seeking = true; r.paint();
  assert.equal(r.status(), 'BUFFERING', 'buffered data does not imply output while seeking');
});

test('source transitions preserve explicit pause and mute and ignore old play promises', async () => {
  const r = fixture(); let finishOldPlay;
  r.setPlay(() => { r.audio.paused = false; return new Promise(resolve => { finishOldPlay = resolve; }); });
  r.setSnapshot(r.state('PLAYING', '1')); await r.boot(); await flush();
  await r.click('playback'); await r.click('mute');
  assert.equal(r.status(), 'PAUSED'); assert.equal(r.audio.muted, true);
  const next = r.state('PLAYING', '2');
  next.round.roundId = 'round-two'; next.round.playbackGeneration = 'generation-two';
  next.media.url = `/media/${'b'.repeat(64)}.ogg`;
  r.apply(next); r.audio.events.seeked(); await r.gesture(); finishOldPlay(); await flush();
  assert.equal(r.plays(), 1); assert.equal(r.audio.paused, true); assert.equal(r.audio.muted, true);
  assert.equal(r.status(), 'PAUSED');
  r.setPlay(null); await r.click('playback');
  await r.advancePlayback();
  assert.equal(r.status(), 'PLAYING'); assert.equal(r.audio.muted, true);
  r.revoke('SESSION_REVOKED'); r.audio.events.seeked(); await r.tick();
  assert.equal(r.audio.paused, true); assert.equal(r.get('connection-text').textContent, '연결 끊김');
});

for (const rate of [1, 2]) test(`a never-visible ${rate}x countdown waits over a minute at its saved position and then starts two sources`, async () => {
  const r = fixture(); let finishPlay;
  const idle = r.state('IDLE', '1'); idle.round = null; idle.media = null;
  r.setSnapshot(idle); await r.boot(); await flush();
  r.audio.load = () => { r.audio.readyState = 0; r.audio.currentTime = 0; r.audio.paused = true; };
  const next = r.state('COUNTDOWN', '2'); Object.assign(next.round, { playing: true, anchorAtMs: 2000, playbackRate: rate });
  r.setClock(2000); r.apply(next); await flush();
  r.setPlay(() => { r.audio.paused = false; return new Promise(resolve => { finishPlay = resolve; }); });
  r.audio.readyState = 4; r.audio.events.canplay(); await flush();
  for (let time = 6000; time <= 76000; time += 5000) {
    r.setClock(time); await r.event('lease', { authorityLeaseExpiresAtMs: time + 15000 }); await r.tick();
    assert.equal(r.audio.currentTime, 0); assert.equal(r.get('progress').value, 0);
    assert.equal(r.status(), 'BUFFERING'); assert.equal(r.reports.some(report => report.status === 'PLAYING'), false);
  }
  assert.equal(r.plays(), 1, 'the unresolved browser play request remains intact');
  r.document.hidden = false; finishPlay(); await flush();
  assert.equal(r.status(), 'BUFFERING');
  await r.advancePlayback(); assert.equal(r.status(), 'PLAYING');
  assert.equal(r.reports.at(-1).positionMs, 250 * rate); assert.equal(r.get('progress').value, 0, 'round time awaits Java confirmation');
  for (let i = 0; i < 4; i++) await r.advancePlayback();
  assert.equal(r.audio.currentTime, 1.25 * rate, 'confirmation heartbeats must not rewind natural playback');
  assert.equal(r.reports.at(-1).positionMs, 1250 * rate, 'fresh progress is renewed while waiting for confirmation');
  const confirmed = r.state('PLAYING', '3'); Object.assign(confirmed.round, { anchorPositionMs: 1250 * rate, playbackRate: rate });
  r.apply(confirmed); await flush(); assert.equal(r.audio.currentTime, 1.25 * rate);
  r.document.hidden = true;
  const second = r.state('COUNTDOWN', '4');
  Object.assign(second.round, { roundId: 'round-two', playbackGeneration: 'generation-two', playing: true });
  second.media.url = `/media/${'b'.repeat(64)}.ogg`;
  r.setPlay(null); r.apply(second); await flush();
  assert.equal(r.audio.readyState, 0); assert.notEqual(r.status(), 'PLAYING');
  r.audio.readyState = 4; r.audio.events.canplay(); await flush();
  assert.equal(r.status(), 'BUFFERING');
  await r.advancePlayback(); assert.equal(r.status(), 'PLAYING');
  assert.equal(r.reports.at(-1).roundId, 'round-two'); assert.equal(r.reports.at(-1).positionMs, 250);
  assert.equal(r.document.hidden, true); assert.equal(r.plays(), 2);
});

test('resume generations need fresh natural progress; seek jumps and settled promises are insufficient', async () => {
  const r = fixture();
  const initial = r.state('COUNTDOWN', '1'); initial.round.playing = true;
  r.setSnapshot(initial); await r.boot(); await flush(); await r.advancePlayback();
  assert.equal(r.status(), 'PLAYING');
  const resumed = r.state('COUNTDOWN', '2');
  Object.assign(resumed.round, { playbackGeneration: 'resume-generation', playing: true, anchorPositionMs: 30000 });
  r.apply(resumed); await flush(); assert.equal(r.audio.currentTime, 30); assert.equal(r.status(), 'BUFFERING');
  assert.equal(r.reports.some(report => report.playbackGeneration === 'resume-generation' && report.status === 'PLAYING'), false);
  r.audio.currentTime = 40; r.setClock(1300); r.paint();
  assert.equal(r.status(), 'BUFFERING', 'a ten-second discontinuity is not natural progress');
  r.audio.events.seeked(); await flush();
  await r.advancePlayback(); assert.equal(r.status(), 'PLAYING');
  assert.equal(r.reports.at(-1).playbackGeneration, 'resume-generation');
  const frozen = r.audio.currentTime; r.setClock(4000); r.paint();
  assert.equal(r.audio.currentTime, frozen); assert.equal(r.status(), 'BUFFERING', 'stalled playback cannot remain PLAYING indefinitely');
});

test('countdown revisions preserve pending playback and renew valid progress for the current revision', async () => {
  const r = fixture(); let finishPlay;
  r.setPlay(() => { r.audio.paused = false; return new Promise(resolve => { finishPlay = resolve; }); });
  const countdown = r.state('COUNTDOWN', '1'); countdown.round.playing = true;
  r.setSnapshot(countdown); await r.boot(); await flush();
  const revise = revision => ({ ...countdown, eventId: `epoch:${revision}`, round: { ...countdown.round, revision } });
  r.setClock(5000); r.apply(revise('2')); await flush();
  assert.equal(r.audio.currentTime, 0); assert.equal(r.plays(), 1); assert.equal(r.status(), 'BUFFERING');
  finishPlay(); await flush(); await r.advancePlayback();
  assert.equal(r.reports.at(-1).status, 'PLAYING'); assert.equal(r.reports.at(-1).roundRevision, '2');
  r.apply(revise('3')); await flush();
  assert.equal(r.status(), 'PLAYING'); assert.equal(r.audio.currentTime, .25); assert.equal(r.plays(), 1);
  assert.equal(r.reports.at(-1).roundRevision, '3'); assert.equal(r.reports.at(-1).positionMs, 250);
  for (let i = 0; i < 16; i++) await r.advancePlayback();
  assert.equal(r.audio.currentTime, 4.25);
  const confirmed = r.state('PLAYING', '4'); confirmed.round.anchorPositionMs = 2000;
  r.apply(confirmed); await flush();
  assert.equal(r.audio.currentTime, 2, 'a bounded authoritative start correction is adopted once Java confirms');
  assert.equal(r.status(), 'BUFFERING', 'the corrective seek does not reuse old progress proof');
  await r.advancePlayback(); assert.equal(r.status(), 'PLAYING');
});

test('a late play promise from an old generation cannot prove a pending resumed generation', async () => {
  const r = fixture(), pending = [];
  r.setPlay(() => { r.audio.paused = false; return new Promise(resolve => { pending.push(resolve); }); });
  const countdown = r.state('COUNTDOWN', '1'); countdown.round.playing = true;
  r.setSnapshot(countdown); await r.boot(); await flush();
  const resumed = r.state('COUNTDOWN', '2');
  Object.assign(resumed.round, { playing: true, playbackGeneration: 'resumed', anchorPositionMs: 10000 });
  r.apply(resumed); await flush(); assert.equal(pending.length, 2);
  pending[0](); await flush(); await r.advancePlayback();
  assert.equal(r.status(), 'BUFFERING');
  assert.equal(r.reports.some(report => report.status === 'PLAYING'), false);
  pending[1](); await flush(); await r.advancePlayback();
  assert.equal(r.status(), 'PLAYING'); assert.equal(r.reports.at(-1).playbackGeneration, 'resumed');
});
