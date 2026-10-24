'use strict';
// Isolated headless Chrome with default autoplay policy; only the connection button receives a click.
// Run separately from the fast VM suite. NODE_PATH may point at an installed Playwright.
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { readFileSync, writeFileSync, mkdirSync, mkdtempSync, rmSync, existsSync } = require('node:fs');
const { resolve, join, dirname } = require('node:path');
const { tmpdir } = require('node:os');
const { createHash } = require('node:crypto');
const { spawn } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');
const { chromium } = require('playwright');
const staticRoot = resolve(__dirname, '../static');

function tone(frequency) {
  const rate = 48000, samples = rate * 5, bytes = Buffer.alloc(44 + samples * 2);
  bytes.write('RIFF'); bytes.writeUInt32LE(bytes.length - 8, 4); bytes.write('WAVEfmt ', 8);
  bytes.writeUInt32LE(16, 16); bytes.writeUInt16LE(1, 20); bytes.writeUInt16LE(1, 22);
  bytes.writeUInt32LE(rate, 24); bytes.writeUInt32LE(rate * 2, 28); bytes.writeUInt16LE(2, 32); bytes.writeUInt16LE(16, 34);
  bytes.write('data', 36); bytes.writeUInt32LE(samples * 2, 40);
  for (let i = 0; i < samples; i++) bytes.writeInt16LE(Math.round(Math.sin(i * frequency * 2 * Math.PI / rate) * 1000), 44 + i * 2);
  return bytes;
}
const tracks = [tone(440), tone(660)].map(bytes => ({ bytes, hash: createHash('sha256').update(bytes).digest('hex') }));
async function fixture() {
  const reports = [], mediaRequests = [], streams = new Set();
  let song = 0, phase = 'PREPARING', revision = 1, seq = 0, anchorAt = 0, anchorPosition = 400;
  function snapshot() {
    const time = Date.now();
    if (phase === 'PLAYING' && anchorPosition + time - anchorAt >= 3400) {
      song++; revision++; phase = song < tracks.length ? 'PREPARING' : 'ENDED'; anchorAt = 0; anchorPosition = 400;
    }
    return { eventId: `fixture:${++seq}`, serverTimeMs: time, authorityLeaseExpiresAtMs: time + 15000,
      me: { uuid: '10000000-0000-4000-8000-000000000001', nickname: 'AUTOPLAY_QA', textureHash: null },
      room: { phase, players: [], roundNumber: Math.min(song + 1, 2), registeredSongs: 2 },
      round: phase === 'ENDED' ? null : { roundId: `round-${song}`, revision: String(revision), playbackGeneration: `play-${song}`,
        number: song + 1, clipStartMs: 400, clipEndMs: 3400, anchorAtMs: anchorAt, anchorPositionMs: anchorPosition,
        playing: ['COUNTDOWN', 'PLAYING'].includes(phase), playbackRate: 1 },
      media: phase === 'ENDED' ? null : { url: `/media/${tracks[song].hash}.ogg`, durationMs: 5000 }, reveal: null };
  }
  const server = createServer(async (req, res) => {
    const path = new URL(req.url, 'http://fixture').pathname;
    const send = (bytes, type = 'application/json', status = 200, headers = {}) => {
      res.writeHead(status, { 'Content-Type': type, 'Content-Length': bytes.length, 'Cache-Control': 'no-store',
        'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' https://textures.minecraft.net; media-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'",
        'Permissions-Policy': 'camera=(), microphone=(), geolocation=()', ...headers }); res.end(bytes);
    };
    const json = data => send(Buffer.from(JSON.stringify(data)));
    if (req.method === 'POST' && path === '/api/v1/playback') {
      let body = ''; for await (const chunk of req) body += chunk;
      const report = JSON.parse(body); reports.push({ at: Date.now(), song, status: report.status, positionMs: report.positionMs });
      if (phase === 'PREPARING' && report.status === 'PREPARED') { phase = 'COUNTDOWN'; revision++; anchorAt = Date.now() + 1200; }
      if (phase === 'COUNTDOWN' && report.status === 'PLAYING' && report.positionMs >= 500) { phase = 'PLAYING'; revision++; anchorAt = Date.now(); anchorPosition = report.positionMs; }
      return json({ leaseExpiresAtMs: Date.now() + 60000 });
    }
    if (path === '/api/v1/me' || path === '/api/v1/auth/redeem') return json({ me: snapshot().me, csrfToken: 'LOCAL_FIXTURE_ONLY' });
    if (path === '/api/v1/time') return json({ receivedAtMs: Date.now(), sentAtMs: Date.now() });
    if (path === '/api/v1/state') return json(snapshot());
    if (path === '/api/v1/events') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' });
      const timer = setInterval(() => res.write(`event: state\ndata: ${JSON.stringify(snapshot())}\n\n`), 500);
      streams.add(res); req.on('close', () => { clearInterval(timer); streams.delete(res); }); return;
    }
    const track = tracks.find(track => path === `/media/${track.hash}.ogg`);
    if (track) {
      mediaRequests.push(path);
      const range = /^bytes=(\d+)-(\d*)$/.exec(req.headers.range || '');
      if (!range) return send(track.bytes, 'audio/wav', 200, { 'Accept-Ranges': 'bytes' });
      const start = Number(range[1]), end = Math.min(Number(range[2] || track.bytes.length - 1), track.bytes.length - 1);
      return send(track.bytes.subarray(start, end + 1), 'audio/wav', 206, { 'Accept-Ranges': 'bytes', 'Content-Range': `bytes ${start}-${end}/${track.bytes.length}` });
    }
    const assets = { '/': ['index.html', 'text/html; charset=utf-8'], '/join': ['index.html', 'text/html; charset=utf-8'], '/app.js': ['app.js', 'text/javascript'],
      '/join.js': ['join.js', 'text/javascript'], '/style.css': ['style.css', 'text/css'], '/playback-check.wav': ['playback-check.wav', 'audio/wav'] };
    if (assets[path]) return send(readFileSync(join(staticRoot, assets[path][0])), assets[path][1]);
    send(Buffer.from('Not found'), 'text/plain', 404);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return { origin: `http://127.0.0.1:${server.address().port}`, reports, mediaRequests, snapshot,
    async close() { for (const stream of streams) stream.end(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); } };
}
async function readState(cdp) {
  const result = await cdp.send('Runtime.evaluate', { userGesture: false, returnByValue: true,
    expression: `JSON.stringify({activated:navigator.userActivation.hasBeenActive,hidden:document.hidden,gateHidden:document.getElementById('connect-screen')?.hidden,buttonDisabled:document.getElementById('connect-button')?.disabled,playerHidden:document.getElementById('player-screen')?.hidden,status:document.getElementById('audio-status')?.textContent,countdown:document.getElementById('countdown')?.textContent,paused:document.getElementById('audio')?.paused,muted:document.getElementById('audio')?.muted,volume:document.getElementById('audio')?.volume,position:document.getElementById('audio')?.currentTime,source:document.getElementById('audio')?.getAttribute('src'),readyState:document.getElementById('audio')?.readyState})` });
  return JSON.parse(result.result.value);
}
async function waitFor(check, timeout = 20000) {
  const until = Date.now() + timeout;
  while (Date.now() < until) { if (await check()) return; await delay(100); }
  throw new Error('Timed out waiting for browser playback');
}
async function run(name, connect, background, reducedMotion = false) {
  const app = await fixture(), profile = mkdtempSync(join(tmpdir(), 'noqui-autoplay-'));
  let context, browser, browserCdp, browserProcess;
  try {
    // Headless avoids desktop occlusion suspending the fixture before its click.
    // No autoplay overrides, muted output, or disabled background throttling.
    browserProcess = spawn(process.env.NOQUI_BROWSER_EXECUTABLE || chromium.executablePath(),
      [`--user-data-dir=${profile}`, '--headless=new', '--window-size=1280,900', '--remote-debugging-port=0', '--no-first-run', '--no-default-browser-check', 'about:blank'],
      { windowsHide: true, stdio: 'ignore' });
    let launchError; browserProcess.on('error', error => { launchError = error; });
    const portFile = join(profile, 'DevToolsActivePort');
    await waitFor(() => { if (launchError) throw launchError; return existsSync(portFile); }, 10000);
    const port = readFileSync(portFile, 'utf8').split('\n')[0];
    browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`, { noDefaults: true }); context = browser.contexts()[0];
    browserCdp = await browser.newBrowserCDPSession();
    const newWindow = context.waitForEvent('page');
    await browserCdp.send('Target.createTarget', { url: 'about:blank', newWindow: true, background: false });
    let page = await newWindow;
    const initialCdp = await context.newCDPSession(page);
    const initialTarget = await initialCdp.send('Target.getTargetInfo'), window = await browserCdp.send('Browser.getWindowForTarget', { targetId: initialTarget.targetInfo.targetId });
    await browserCdp.send('Browser.setWindowBounds', { windowId: window.windowId, bounds: { windowState: 'maximized' } });
    await initialCdp.send('Page.bringToFront');
    const cdp = await context.newCDPSession(page), samples = [];
    // Playwright enables focus emulation on every page. Turn it off before the
    // production script loads so document.hidden reflects a real background tab.
    await cdp.send('Emulation.setFocusEmulationEnabled', { enabled: false });
    if (reducedMotion) {
      await cdp.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
      await cdp.send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    }
    await cdp.send('Page.navigate', { url: connect ? `${app.origin}/join#t=${'a'.repeat(43)}` : app.origin });
    await waitFor(async () => (await readState(cdp)).buttonDisabled === false);
    await cdp.send('Page.bringToFront');
    await cdp.send('Runtime.evaluate', { userGesture: false, expression: "globalThis.__qaAudio = document.getElementById('audio')" });
    assert.equal((await readState(cdp)).activated, false);
    assert.equal(app.reports.length, 0);
    const screenshotDir = resolve(__dirname, '../target/connection-preview'); mkdirSync(screenshotDir, { recursive: true });
    const shot = async suffix => { const shot = await cdp.send('Page.captureScreenshot', { format: 'png' }); writeFileSync(join(screenshotDir, `${name}-${suffix}.png`), Buffer.from(shot.data, 'base64')); };
    await shot('connect');
    if (connect) {
      const bounds = await cdp.send('Runtime.evaluate', { userGesture: false, returnByValue: true, expression: "(() => { const r=document.getElementById('connect-button').getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2}; })()" });
      await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', ...bounds.result.value, button: 'left', clickCount: 1 });
      await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...bounds.result.value, button: 'left', clickCount: 1 });
      try { await waitFor(async () => (await readState(cdp)).gateHidden); }
      catch (error) {
        const detail = await cdp.send('Runtime.evaluate', { userGesture: false, returnByValue: true, expression: "JSON.stringify({status:document.getElementById('connect-status').textContent,scripts:[...document.scripts].map(s=>s.src),visibility:document.visibilityState})" });
        console.error(JSON.stringify({ name, state: await readState(cdp), detail: detail.result.value })); await shot('failed'); throw error;
      }
      const identity = await cdp.send('Runtime.evaluate', { userGesture: false, returnByValue: true, expression: "globalThis.__qaAudio === document.getElementById('audio') && __qaAudio.isConnected && location.pathname==='/' && location.hash===''" });
      assert.equal(identity.result.value, true);
      await shot('player');
    }
    if (background) {
      await browserCdp.send('Target.createTarget', { url: 'about:blank', background: false });
      await waitFor(async () => (await readState(cdp)).hidden);
    }
    const began = Date.now();
    try { await waitFor(async () => {
      const sample = await readState(cdp); samples.push(sample); assert.equal(sample.activated, connect, 'only the initial connection click is allowed');
      if (background) assert.equal(sample.hidden, true, 'the player must remain in a background tab');
      return connect ? app.snapshot().room.phase === 'ENDED' : Date.now() - began >= 1500;
    }); } catch (error) {
      console.error(JSON.stringify({ name, state: await readState(cdp), reports: app.reports.slice(-10), window: await browserCdp.send('Browser.getWindowBounds', { windowId: window.windowId }) }));
      throw error;
    }
    if (connect) {
      for (let song = 0; song < tracks.length; song++) {
        assert(app.reports.some(report => report.song === song && report.status === 'PREPARED'));
        assert(app.reports.some(report => report.song === song && report.status === 'PLAYING' && report.positionMs >= 500));
      }
      assert(samples.some(sample => !sample.paused && !sample.muted && sample.volume > 0 && sample.position > .5));
    } else {
      assert.equal(app.reports.length, 0);
      const sample = await readState(cdp); assert.equal(sample.countdown, ''); assert.equal(sample.activated, false);
      assert.equal(app.snapshot().room.phase, 'PREPARING');
      assert.equal(sample.playerHidden, true); assert.equal(sample.gateHidden, false);
    }
    const evidence = { name, defaultOriginPolicy: true, background, reducedMotion, outcome: connect ? 'two-tracks-after-one-connection-click' : 'gate-waits-without-playback',
      appSha256: createHash('sha256').update(readFileSync(join(staticRoot, 'app.js'))).digest('hex'), browser: (await cdp.send('Browser.getVersion')).product,
      joinSha256: createHash('sha256').update(readFileSync(join(staticRoot, 'join.js'))).digest('hex'),
      activated: samples.some(sample => sample.activated), reports: app.reports, samples: samples.filter((_, index) => index % 5 === 0) };
    console.log(JSON.stringify(evidence)); return evidence;
  } finally {
    await browserCdp?.send('Browser.close').catch(() => {}); await browser?.close();
    if (browserProcess && browserProcess.exitCode === null) await Promise.race([new Promise(resolve => browserProcess.once('exit', resolve)), delay(2000)]);
    await app.close(); assert.equal(dirname(profile), tmpdir());
    rmSync(profile, { recursive: true, force: true, maxRetries: 20, retryDelay: 200 });
  }
}
(async () => {
  for (const args of [['fresh-default-unconnected', false, false], ['fresh-default-connected', true, false], ['fresh-default-background', true, true], ['reduced-motion-mobile', true, false, true]])
    if (!process.argv[2] || process.argv[2] === args[0]) await run(...args);
})().catch(error => { console.error(error); process.exitCode = 1; });
