'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const setText = (id, text) => { const element = $(id); if (element.textContent !== text) element.textContent = text; };
  const audio = $('audio');
  const clientId = sessionStorage.getItem('noqui-client') || crypto.randomUUID();
  sessionStorage.setItem('noqui-client', clientId);
  let state = null, csrf = '', stream = null, connectionGeneration = 0, reconnectTimer = null, retries = 0;
  let offset = 0, calibrated = false, lastControl = -Infinity, authority = 0, leader = true;
  let currentMedia = '', lastGeneration = '', lastSeek = -Infinity, playPending = null, ackPending = false, ackAgain = false, takeover = false, formatFailed = false;
  let gestureBlocked = false, userPaused = false, clientLeaseUntil = 0, authGeneration = 0;
  let lastStatus = '', authValid = false, eventEpoch = '', eventSeq = -1n, lastPoll = 0;
  let volume = Number(localStorage.getItem('noqui-volume') ?? 65);
  if (!Number.isFinite(volume)) volume = 65;
  volume = Math.max(0, Math.min(100, volume));
  audio.volume = volume / 100; audio.muted = volume === 0; $('volume').value = String(volume); $('volume-value').value = String(volume);
  const now = () => performance.now() + offset;
  const format = value => { const s = Math.max(0, Math.floor(value / 1000)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };
  const message = text => setText('message', text);
  const errors = { AUTH_REQUIRED: '연결 링크가 필요합니다', SESSION_REVOKED: '연결이 종료되었습니다', PLUGIN_OFFLINE: '게임 연결을 기다리는 중', ACTIVE_CLIENT: '다른 창에서 재생 중', FORMAT_UNSUPPORTED: '이 브라우저는 오디오를 지원하지 않습니다', MEDIA_NOT_READY: '곡을 준비하는 중', RATE_LIMITED: '잠시 후 다시 연결합니다' };
  async function request(path, options = {}) {
    const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', ...options });
    if (!response.ok) {
      const result = await response.json().catch(() => ({}));
      const error = new Error(result.error?.code || 'NETWORK'); error.status = response.status;
      if (response.status === 401) revoke(error.message);
      throw error;
    }
    return response.status === 204 ? null : response.json();
  }
  function revoke(code) {
    authGeneration++; authValid = false; clientLeaseUntil = 0; playPending = null; audio.pause(); stream?.close(); clearTimeout(reconnectTimer);
    $('connection').classList.remove('connected'); $('connection-text').textContent = '연결 끊김';
    $('audio-status').textContent = '연결 대기'; $('playback').hidden = true; $('takeover').hidden = true; message(errors[code] || errors.AUTH_REQUIRED);
  }
  function avatar(container, hash) {
    const valid = /^[a-f0-9]{64}$/.test(hash || '');
    for (const image of container.querySelectorAll('img')) {
      image.hidden = !valid;
      if (valid) { const url = `https://textures.minecraft.net/texture/${hash}`; if (image.getAttribute('src') !== url) image.src = url; image.onerror = () => { image.hidden = true; }; }
      else image.removeAttribute('src');
    }
  }
  function makeHead(hash) {
    const head = $('avatar').cloneNode(true); head.removeAttribute('id'); avatar(head, hash); return head;
  }
  function players(list) {
    const fragment = document.createDocumentFragment();
    for (const player of list) {
      const item = document.createElement('div'); item.className = `listener${player.browserOnline ? ' live' : ''}`;
      item.append(makeHead(player.textureHash));
      const name = document.createElement('span'); name.textContent = player.nickname; item.append(name);
      const point = document.createElement('i'); point.className = 'listener-dot'; point.setAttribute('role', 'img'); point.setAttribute('aria-label', player.browserOnline ? '연결됨' : '연결 대기'); item.append(point); fragment.append(item);
    }
    $('listeners').replaceChildren(fragment); $('listener-count').textContent = String(list.filter(p => p.browserOnline).length);
  }
  async function calibrate() {
    const samples = [];
    for (let i = 0; i < 5 && authValid; i++) {
      const start = performance.now(); const data = await request('/api/v1/time'); const end = performance.now();
      samples.push({ rtt: end - start, offset: (data.receivedAtMs + data.sentAtMs) / 2 - (start + end) / 2 });
    }
    samples.sort((a, b) => a.rtt - b.rtt);
    if (samples[0]?.rtt < 1500) { offset = samples[0].offset; calibrated = true; }
  }
  function apply(next) {
    const split = String(next.eventId).lastIndexOf(':'); const epoch = next.eventId.slice(0, split); let seq;
    try { seq = BigInt(next.eventId.slice(split + 1)); } catch { return; }
    if (epoch === eventEpoch && seq < eventSeq) return;
    if (epoch !== eventEpoch) { eventEpoch = epoch; eventSeq = -1n; }
    eventSeq = seq; state = next; lastControl = performance.now(); authority = next.authorityLeaseExpiresAtMs;
    if (next.round?.playbackGeneration !== lastGeneration) { lastGeneration = next.round?.playbackGeneration || ''; lastSeek = -Infinity; playPending = null; audio.pause(); }
    if (!calibrated) offset = next.serverTimeMs - performance.now();
    $('nickname').textContent = next.me.nickname; avatar($('avatar'), next.me.textureHash); players(next.room.players);
    const revealed = next.room.phase === 'REVEAL' && next.reveal;
    setText('track-title', next.room.phase === 'ENDED' ? '노래퀴즈 종료' : revealed ? next.reveal.title : next.round ? '지금 흐르는 곡' : '다음 곡을 기다리는 중');
    setText('phase-label', revealed ? '정답 공개' : ({ WAITING_FOR_MEDIA: '곡 준비', PREPARING: '재생 준비', COUNTDOWN: '곧 시작', PLAYING: '현재 재생', PAUSED: '잠시 멈춤', ENDED: '종료' }[next.room.phase] || '다음 라운드'));
    const { roundNumber, totalRounds } = next.room;
    const hasRoundProgress = Number.isInteger(roundNumber) && roundNumber >= 0 && roundNumber <= 4294967295
      && Number.isInteger(totalRounds) && totalRounds >= 0 && totalRounds <= 2147483647
      && (totalRounds === 0 || roundNumber <= totalRounds);
    let roundLabel = next.round ? `${String(next.round.number).padStart(2, '0')} 라운드` : '대기실';
    if (hasRoundProgress) {
      const number = String(roundNumber).padStart(2, '0');
      roundLabel = totalRounds === 0 ? `${number} 라운드 · 무제한` : `${number} / ${totalRounds} 라운드`;
    }
    setText('round-label', roundLabel);
    $('tags').hidden = !revealed || !next.reveal.tags.length;
    $('tags').replaceChildren(...(revealed ? next.reveal.tags.map(tag => { const el = document.createElement('span'); el.textContent = tag; return el; }) : []));
    if (next.media?.url !== currentMedia) {
      playPending = null; audio.pause(); currentMedia = next.media?.url || ''; lastSeek = -Infinity; formatFailed = false;
      if (currentMedia) {
        if (!/^\/media\/[a-f0-9]{64}\.ogg$/.test(currentMedia) || !audio.canPlayType('audio/ogg; codecs="opus"')) { formatFailed = true; message(errors.FORMAT_UNSUPPORTED); report('FAILED'); return; }
        audio.preload = 'auto'; audio.src = currentMedia; audio.load();
      } else { audio.removeAttribute('src'); audio.load(); }
    }
    if (!revealed) message(next.room.phase === 'WAITING_FOR_MEDIA' ? '곡을 준비하는 중' : '');
    align(); paint();
  }
  function status() {
    if (!leader) return 'CONNECTED';
    if (!state?.round || !currentMedia) return 'CONNECTED';
    if (audio.error || formatFailed) return 'FAILED';
    if (userPaused) return 'PAUSED';
    if (gestureBlocked) return 'NEEDS_GESTURE';
    if (!audio.paused) return audio.readyState >= 3 ? 'PLAYING' : 'BUFFERING';
    if (audio.seeking || audio.readyState < 3) return 'LOADING';
    if (state.room.phase === 'PREPARING' || state.room.phase === 'COUNTDOWN') {
      const target = expected(state.round) / 1000;
      const requiredEnd = Math.min(target + .5, state.round.clipEndMs / 1000);
      let buffered = false;
      for (let i = 0; i < audio.buffered.length; i++) if (audio.buffered.start(i) <= target + .05 && audio.buffered.end(i) >= requiredEnd) buffered = true;
      return !audio.seeking && Math.abs(audio.currentTime - target) < .75 && buffered ? 'PREPARED' : 'LOADING';
    }
    return audio.paused ? 'PAUSED' : 'BUFFERING';
  }
  async function report(forced) {
    if (!authValid || !csrf || !leader && !takeover) return;
    if (ackPending) { ackAgain = true; return; }
    ackPending = true;
    const generation = authGeneration;
    const current = forced || status(); const r = state?.round;
    let bufferedUntilMs = null;
    for (let i = 0; i < audio.buffered.length; i++) if (audio.buffered.start(i) <= audio.currentTime && audio.buffered.end(i) >= audio.currentTime) bufferedUntilMs = Math.round(audio.buffered.end(i) * 1000);
    try {
      const response = await request('/api/v1/playback', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Noqui-CSRF': csrf }, body: JSON.stringify({ clientId, takeOver: takeover, roundId: r?.roundId ?? null, roundRevision: r?.revision ?? null, playbackGeneration: r?.playbackGeneration ?? null, status: current, positionMs: currentMedia && Number.isFinite(audio.currentTime) ? Math.round(audio.currentTime * 1000) : null, bufferedUntilMs, lastEventId: state?.eventId ?? null }) });
      if (!authValid || generation !== authGeneration) return;
      clientLeaseUntil = response.leaseExpiresAtMs;
      takeover = false; lastStatus = current;
      align(); paint();
    } catch (error) {
      if (generation !== authGeneration) return;
      if (error.message === 'ACTIVE_CLIENT') { leader = false; takeover = false; clientLeaseUntil = 0; playPending = null; audio.pause(); $('takeover').hidden = false; message(errors.ACTIVE_CLIENT); }
    } finally { ackPending = false; if (ackAgain) { ackAgain = false; void report(); } }
  }
  function expected(r) { return Math.min(r.clipEndMs, Math.max(r.clipStartMs, r.anchorPositionMs + (r.playing ? Math.max(0, now() - r.anchorAtMs) : 0))); }
  function connected() { return authValid && navigator.onLine && performance.now() - lastControl < 12000 && now() < authority; }
  function wantsPlayback() {
    const r = state?.round;
    return connected() && leader && now() < clientLeaseUntil && !userPaused && !gestureBlocked && r && currentMedia && !formatFailed && !audio.error
      && r.playing && now() >= r.anchorAtMs && expected(r) < r.clipEndMs;
  }
  function align() {
    const r = state?.round;
    if (!connected() || !leader || now() >= clientLeaseUntil || !r || !currentMedia || formatFailed || audio.error) { audio.pause(); return; }
    const target = expected(r) / 1000;
    if (audio.readyState >= 1 && !audio.seeking && Math.abs(audio.currentTime - target) > .5 && performance.now() - lastSeek >= 2000) {
      try { audio.currentTime = target; lastSeek = performance.now(); } catch { /* Seek becomes available after metadata. */ }
    }
    if (!wantsPlayback()) { audio.pause(); return; }
    if (audio.paused && !playPending && audio.readyState >= 2 && !audio.seeking) {
      const attempt = { media: currentMedia, generation: lastGeneration, auth: authGeneration };
      playPending = attempt;
      audio.play().then(() => {
        if (!wantsPlayback()) audio.pause();
      }).catch(error => {
        if (playPending !== attempt || attempt.media !== currentMedia || attempt.generation !== lastGeneration || attempt.auth !== authGeneration) return;
        if (error.name === 'NotAllowedError') { gestureBlocked = true; audio.pause(); }
      }).finally(() => {
        if (playPending === attempt) playPending = null;
        paint();
      });
    }
  }
  function paint() {
    const healthy = connected();
    $('connection').classList.toggle('connected', healthy); $('connection-text').textContent = healthy ? '연결됨' : '연결 대기';
    const r = state?.round; const s = status();
    const names = { CONNECTED: '대기 중', NEEDS_GESTURE: '재생 대기', LOADING: '오디오 준비 중', PREPARED: '재생 준비 완료', PLAYING: '재생 중', PAUSED: '일시 정지', BUFFERING: '버퍼링 중', FAILED: '오디오 오류' };
    setText('audio-status', !healthy ? '연결 대기' : !leader ? '다른 창에서 재생 중' : names[s]);
    $('playback').hidden = !authValid || !leader || !r || !currentMedia;
    const playbackLabel = userPaused || gestureBlocked ? '재생' : '일시 정지';
    setText('playback', playbackLabel); $('playback').setAttribute('aria-label', playbackLabel);
    if (r) {
      const progress = Math.max(0, Math.min(r.clipEndMs - r.clipStartMs, expected(r) - r.clipStartMs));
      $('progress').max = r.clipEndMs - r.clipStartMs; $('progress').value = progress;
      $('elapsed').textContent = format(progress); $('remaining').textContent = format(r.clipEndMs - r.clipStartMs - progress);
      $('countdown').textContent = r.playing && now() < r.anchorAtMs ? String(Math.ceil((r.anchorAtMs - now()) / 1000)) : '';
    } else { $('progress').value = 0; $('elapsed').textContent = '0:00'; $('remaining').textContent = '—'; $('countdown').textContent = ''; }
    if (s !== lastStatus) { lastStatus = s; void report(s); }
  }
  function connect() {
    clearTimeout(reconnectTimer); stream?.close(); const generation = ++connectionGeneration;
    stream = new EventSource('/api/v1/events');
    stream.addEventListener('state', event => { if (generation !== connectionGeneration) return; try { apply(JSON.parse(event.data)); retries = 0; } catch { message('상태를 다시 확인하는 중'); } });
    stream.addEventListener('lease', event => { if (generation !== connectionGeneration) return; try { const data = JSON.parse(event.data); authority = data.authorityLeaseExpiresAtMs; lastControl = performance.now(); } catch { /* The next snapshot repairs control state. */ } });
    stream.addEventListener('revoked', () => { if (generation === connectionGeneration) revoke('SESSION_REVOKED'); });
    stream.onerror = () => {
      if (generation !== connectionGeneration) return; stream.close();
      if (!authValid) return;
      const delay = Math.min(15000, 1000 * 2 ** Math.min(retries++, 4)) * (.8 + Math.random() * .4);
      reconnectTimer = setTimeout(async () => { try { apply(await request('/api/v1/state')); connect(); } catch { if (authValid) connect(); } }, delay);
    };
  }
  async function boot() {
    const generation = ++authGeneration;
    clientLeaseUntil = 0;
    try {
      const data = await request('/api/v1/me'); if (generation !== authGeneration) return; csrf = data.csrfToken; authValid = true;
      $('nickname').textContent = data.me.nickname; avatar($('avatar'), data.me.textureHash);
      await calibrate(); const next = await request('/api/v1/state'); if (generation !== authGeneration) return; apply(next); connect(); void report();
    } catch (error) { if (error.status !== 401) { message('연결을 확인할 수 없습니다'); $('audio-status').textContent = '연결 대기'; } }
  }
  $('playback').addEventListener('click', () => {
    if (userPaused || gestureBlocked) { userPaused = false; gestureBlocked = false; align(); }
    else { userPaused = true; playPending = null; audio.pause(); }
    paint();
  });
  const retryGesture = event => {
    if (!event.isTrusted || event.repeat || !gestureBlocked || userPaused || event.target?.closest?.('#playback, #takeover, #logout, #reconnect')) return;
    if (event.type === 'keydown' && (event.ctrlKey || event.metaKey || event.altKey || ['Shift', 'Control', 'Alt', 'Meta', 'Tab'].includes(event.key))) return;
    gestureBlocked = false; align(); paint();
  };
  for (const event of ['pointerup', 'click', 'keydown']) document.addEventListener(event, retryGesture);
  $('takeover').addEventListener('click', async () => { leader = true; takeover = true; clientLeaseUntil = 0; gestureBlocked = false; await report(); if (leader && authValid && now() < clientLeaseUntil) { $('takeover').hidden = true; align(); paint(); } });
  $('volume').addEventListener('input', () => { volume = Number($('volume').value); audio.volume = volume / 100; audio.muted = volume === 0; $('volume-value').value = String(volume); localStorage.setItem('noqui-volume', String(volume)); $('mute').setAttribute('aria-pressed', String(audio.muted)); });
  $('mute').addEventListener('click', () => { audio.muted = !audio.muted; $('mute').textContent = audio.muted ? '음소거' : '소리'; $('mute').setAttribute('aria-pressed', String(audio.muted)); });
  $('reconnect').addEventListener('click', () => { if (authValid) { void calibrate().then(() => request('/api/v1/state')).then(next => { apply(next); connect(); }).catch(() => {}); } else void boot(); });
  $('logout').addEventListener('click', async () => { try { await request('/api/v1/auth/logout', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Noqui-CSRF': csrf }, body: '{}' }); } finally { revoke('SESSION_REVOKED'); } });
  for (const event of ['loadedmetadata', 'canplay', 'seeked', 'playing', 'pause', 'waiting', 'ended', 'error']) audio.addEventListener(event, () => { align(); paint(); if (event === 'error' && currentMedia) message('오디오를 불러올 수 없습니다'); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && authValid) void calibrate().then(() => request('/api/v1/state')).then(apply).catch(() => {}); });
  window.addEventListener('online', () => { if (authValid) { connect(); void calibrate().catch(() => {}); } });
  window.addEventListener('offline', () => paint());
  setInterval(() => { align(); paint(); if (!document.hidden && authValid && stream?.readyState !== EventSource.OPEN && performance.now() - lastPoll > 2000) { lastPoll = performance.now(); void request('/api/v1/state').then(apply).catch(() => {}); } }, 250);
  setInterval(() => void report(), 15000);
  void boot();
})();
