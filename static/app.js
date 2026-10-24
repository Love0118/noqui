'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const setText = (id, text) => { const element = $(id); if (element.textContent !== text) element.textContent = text; };
  const audio = $('audio');
  // Duplicated tabs inherit sessionStorage: ownership must identify this document.
  const clientId = crypto.randomUUID();
  let state = null, csrf = '', stream = null, connectionGeneration = 0, reconnectTimer = null, retries = 0;
  let offset = 0, calibrated = false, lastControl = -Infinity, authority = 0, leader = true;
  let currentMedia = '', lastGeneration = '', lastSeek = -Infinity, playPending = null, ackPending = false, ackAgain = false, takeover = false, formatFailed = false;
  let gestureBlocked = false, userPaused = false, clientLeaseUntil = 0, authGeneration = 0, playbackGesture = 0;
  let countdownStarted = false, progressSample = null, progressStart = null, lastProgressAt = -Infinity;
  let outputCheck = null, checkedOutput = '', nextAutoplayAttemptAt = 0;
  let authValid = false, eventEpoch = '', eventSeq = -1n, lastPoll = -Infinity;
  let lastReportSignature = '', inFlightSignature = '', lastReportAt = -Infinity, nextReportAt = 0, refreshing = null;
  let boundPlayer = sessionStorage.getItem('noqui-player') || '', boundCsrf = '';
  let volume = Number(localStorage.getItem('noqui-volume') ?? 65);
  if (!Number.isFinite(volume)) volume = 65;
  volume = Math.max(0, Math.min(100, volume));
  audio.volume = volume / 100; audio.muted = volume === 0; $('volume').value = String(volume); $('volume-value').value = String(volume);
  const now = () => performance.now() + offset;
  const format = value => { const s = Math.max(0, Math.floor(value / 1000)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };
  const message = text => setText('message', text);
  const errors = { AUTH_REQUIRED: '연결 링크가 필요합니다', SESSION_REVOKED: '연결이 종료되었습니다', SESSION_CHANGED: '새 연결 링크 필요', PLUGIN_OFFLINE: '게임 연결을 기다리는 중', ACTIVE_CLIENT: '다른 창에서 재생 중', FORMAT_UNSUPPORTED: '이 브라우저는 오디오를 지원하지 않습니다', MEDIA_NOT_READY: '대기 중', RATE_LIMITED: '잠시 후 다시 연결합니다' };
  async function request(path, options = {}) {
    const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', ...options });
    if (!response.ok) {
      const result = await response.json().catch(() => ({}));
      const error = new Error(result.error?.code || 'NETWORK'); error.status = response.status;
      if (response.status === 401) revoke(error.message);
      if (response.status === 403 && error.message === 'CSRF_REJECTED') revoke('SESSION_CHANGED');
      if (response.status === 503 && error.message === 'PLUGIN_OFFLINE') pluginOffline();
      throw error;
    }
    return response.status === 204 ? null : response.json();
  }
  function revoke(code) {
    authGeneration++; connectionGeneration++; authValid = false; clientLeaseUntil = 0; playPending = null; cancelOutputCheck(); checkedOutput = ''; audio.pause(); stream?.close(); clearTimeout(reconnectTimer);
    $('connection').classList.remove('connected'); $('connection-text').textContent = code === 'SESSION_CHANGED' ? '다른 연결' : '연결 끊김';
    $('logout').disabled = true;
    $('audio-status').textContent = '연결 대기'; $('playback').hidden = true; $('takeover').hidden = true; message(errors[code] || errors.AUTH_REQUIRED);
  }
  function pluginOffline() {
    if (!authValid) return;
    authority = 0; align(); paint(); message(errors.PLUGIN_OFFLINE);
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
    if (boundPlayer && next.me.uuid !== boundPlayer) { revoke('SESSION_CHANGED'); return; }
    const split = String(next.eventId).lastIndexOf(':'); const epoch = next.eventId.slice(0, split); let seq;
    try { seq = BigInt(next.eventId.slice(split + 1)); } catch { return; }
    if (epoch === eventEpoch && seq < eventSeq) return;
    if (epoch !== eventEpoch) { eventEpoch = epoch; eventSeq = -1n; }
    if (next.round?.roundId !== state?.round?.roundId || next.media?.url !== currentMedia) { cancelOutputCheck(); checkedOutput = ''; }
    eventSeq = seq; state = next; lastControl = performance.now(); authority = next.authorityLeaseExpiresAtMs;
    if (next.round?.playbackGeneration !== lastGeneration) { lastGeneration = next.round?.playbackGeneration || ''; lastSeek = -Infinity; playPending = null; countdownStarted = false; resetProgress(); audio.pause(); }
    if (!calibrated) offset = next.serverTimeMs - performance.now();
    $('nickname').textContent = next.me.nickname; avatar($('avatar'), next.me.textureHash); players(next.room.players);
    const revealed = next.room.phase === 'REVEAL' && next.reveal;
    setText('track-title', next.room.phase === 'ENDED' ? '노래퀴즈 종료' : revealed ? next.reveal.title : next.round ? '지금 흐르는 곡' : '다음 곡을 기다리는 중');
    setText('phase-label', revealed ? '정답 공개' : ({ WAITING_FOR_MEDIA: '대기 중', PREPARING: '대기 중', COUNTDOWN: '곧 시작', PLAYING: '현재 재생', PAUSED: '잠시 멈춤', ENDED: '종료' }[next.room.phase] || '다음 라운드'));
    const { roundNumber, registeredSongs } = next.room;
    const hasRoundNumber = Number.isInteger(roundNumber) && roundNumber >= 0 && roundNumber <= 4294967295;
    const hasRegisteredCount = Number.isInteger(registeredSongs) && registeredSongs >= 0 && registeredSongs <= 4294967295;
    let roundLabel = next.round ? `${next.round.number} 라운드` : hasRoundNumber && roundNumber > 0 ? `${roundNumber} 라운드` : '대기실';
    if (hasRoundNumber && hasRegisteredCount) roundLabel = `${roundNumber}/${registeredSongs}`;
    setText('round-label', roundLabel);
    $('tags').hidden = !revealed || !next.reveal.tags.length;
    $('tags').replaceChildren(...(revealed ? next.reveal.tags.map(tag => { const el = document.createElement('span'); el.textContent = tag; return el; }) : []));
    if (next.media?.url !== currentMedia) {
      playPending = null; countdownStarted = false; resetProgress(); audio.pause(); currentMedia = next.media?.url || ''; lastSeek = -Infinity; formatFailed = false;
      if (currentMedia) {
        if (!/^\/media\/[a-f0-9]{64}\.ogg$/.test(currentMedia) || !audio.canPlayType('audio/ogg; codecs="opus"')) { formatFailed = true; message(errors.FORMAT_UNSUPPORTED); report('FAILED'); return; }
        audio.preload = 'auto'; audio.src = currentMedia; audio.load();
      } else { audio.removeAttribute('src'); audio.load(); }
    }
    audio.defaultPlaybackRate = playbackRate(next.round);
    audio.playbackRate = audio.defaultPlaybackRate;
    audio.preservesPitch = audio.playbackRate === 1;
    if (!revealed || $('message').textContent === errors.PLUGIN_OFFLINE) message('');
    align(); paint();
  }
  function resetProgress() { progressSample = null; progressStart = null; lastProgressAt = -Infinity; }
  function outputKey() { return `${state?.round?.roundId}:${currentMedia}`; }
  function cancelOutputCheck() {
    if (!outputCheck) return;
    const attempt = outputCheck;
    // Keep the guard in place during pause/load events from the temporary source.
    audio.pause(); audio.volume = attempt.volume; audio.muted = attempt.muted;
    if (currentMedia) audio.src = currentMedia; else audio.removeAttribute('src');
    audio.load(); lastSeek = -Infinity; resetProgress(); outputCheck = null;
  }
  function checkOutput() {
    const attempt = { key: outputKey(), auth: authGeneration, gesture: playbackGesture, volume: audio.volume, muted: audio.muted };
    outputCheck = attempt;
    // An unmuted silent WAV checks the browser's audible-playback permission on
    // this same element without revealing the quiz. It does not grant permission.
    audio.src = '/playback-check.wav'; audio.volume = .65; audio.muted = false; audio.load();
    audio.play().then(() => {
      if (outputCheck !== attempt || attempt.key !== outputKey() || attempt.auth !== authGeneration) return;
      if (connected() && leader && now() < clientLeaseUntil && !userPaused && !audio.paused && audio.readyState >= 2) { checkedOutput = attempt.key; gestureBlocked = false; }
    }).catch(error => {
      if (outputCheck !== attempt) return;
      if (error.name === 'NotAllowedError') { gestureBlocked = attempt.gesture === playbackGesture; nextAutoplayAttemptAt = performance.now() + 15000; }
      else if (error.name !== 'AbortError') { formatFailed = true; }
    }).finally(() => {
      if (outputCheck !== attempt) return;
      cancelOutputCheck(); align(); paint();
    });
  }
  function naturallyPlaying() {
    if (audio.paused || audio.seeking || playPending || audio.readyState < 3 || !wantsPlayback()) { resetProgress(); return false; }
    const sample = { at: performance.now(), position: audio.currentTime };
    if (progressSample && sample.at > progressSample.at) {
      const elapsed = sample.at - progressSample.at, advanced = sample.position - progressSample.position;
      if (advanced < 0 || advanced > elapsed / 1000 * playbackRate(state.round) + .25) { progressStart = null; lastProgressAt = -Infinity; }
      else if (advanced > 0) { progressStart ||= progressSample; lastProgressAt = sample.at; }
      if (sample.at - lastProgressAt > 2000) progressStart = null;
    }
    progressSample = sample;
    return progressStart !== null && sample.at - progressStart.at >= 100
      && sample.position - progressStart.position >= .1 * playbackRate(state.round) && sample.at - lastProgressAt <= 2000;
  }
  function status() {
    if (audio.paused || audio.seeking || playPending) resetProgress();
    if (!leader) return 'CONNECTED';
    if (!state?.round || !currentMedia) return 'CONNECTED';
    if (audio.error || formatFailed) return 'FAILED';
    if (userPaused) return 'PAUSED';
    if (gestureBlocked) return 'NEEDS_GESTURE';
    if (outputCheck) return 'BUFFERING';
    if (playPending) return 'BUFFERING';
    if (!audio.paused) return naturallyPlaying() ? 'PLAYING' : 'BUFFERING';
    if (audio.seeking || audio.readyState < 3) return 'LOADING';
    if (state.room.phase === 'PREPARING' || state.room.phase === 'COUNTDOWN') {
      if (checkedOutput !== outputKey()) return 'LOADING';
      const target = expected(state.round) / 1000;
      const requiredEnd = Math.min(target + .5 * playbackRate(state.round), state.round.clipEndMs / 1000);
      let buffered = false;
      for (let i = 0; i < audio.buffered.length; i++) if (audio.buffered.start(i) <= target + .05 && audio.buffered.end(i) >= requiredEnd) buffered = true;
      return !audio.seeking && Math.abs(audio.currentTime - target) < .75 && buffered ? 'PREPARED' : 'LOADING';
    }
    return audio.paused ? 'PAUSED' : 'BUFFERING';
  }
  async function report(forced) {
    if (!authValid || !csrf || !leader && !takeover) return;
    const current = forced || status(); const r = state?.round;
    const signature = JSON.stringify([current, r?.roundId, r?.revision, r?.playbackGeneration, takeover]);
    if (ackPending) { if (signature !== inFlightSignature) ackAgain = true; return; }
    const heartbeat = state?.room.phase === 'COUNTDOWN' && current === 'PLAYING' ? 1000 : 15000;
    if (signature === lastReportSignature && performance.now() - lastReportAt < heartbeat) return;
    if (!takeover && performance.now() < nextReportAt) return;
    ackPending = true;
    inFlightSignature = signature;
    const generation = authGeneration;
    let bufferedUntilMs = null;
    if (!outputCheck) for (let i = 0; i < audio.buffered.length; i++) if (audio.buffered.start(i) <= audio.currentTime && audio.buffered.end(i) >= audio.currentTime) bufferedUntilMs = Math.round(audio.buffered.end(i) * 1000);
    try {
      const response = await request('/api/v1/playback', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Noqui-CSRF': csrf }, body: JSON.stringify({ clientId, takeOver: takeover, roundId: r?.roundId ?? null, roundRevision: r?.revision ?? null, playbackGeneration: r?.playbackGeneration ?? null, status: current, positionMs: !outputCheck && currentMedia && Number.isFinite(audio.currentTime) ? Math.round(audio.currentTime * 1000) : null, bufferedUntilMs, lastEventId: state?.eventId ?? null }) });
      if (!authValid || generation !== authGeneration) return;
      clientLeaseUntil = response.leaseExpiresAtMs;
      takeover = false; lastReportSignature = signature; lastReportAt = performance.now(); nextReportAt = 0;
      align(); paint();
    } catch (error) {
      if (generation !== authGeneration) return;
      if (error.message === 'ACTIVE_CLIENT') { leader = false; takeover = false; clientLeaseUntil = 0; playPending = null; cancelOutputCheck(); audio.pause(); $('takeover').hidden = false; message(errors.ACTIVE_CLIENT); }
      else nextReportAt = performance.now() + 2000;
    } finally { ackPending = false; if (ackAgain) { ackAgain = false; void report(); } }
  }
  function playbackRate(r) { return r?.playbackRate === 2 ? 2 : 1; }
  function expected(r) { return Math.min(r.clipEndMs, Math.max(r.clipStartMs, r.anchorPositionMs + (r.playing && state?.room.phase !== 'COUNTDOWN' ? Math.max(0, now() - r.anchorAtMs) * playbackRate(r) : 0))); }
  function connected() { return authValid && navigator.onLine && now() < authority; }
  function wantsPlayback() {
    const r = state?.round;
    return connected() && leader && now() < clientLeaseUntil && !userPaused && !gestureBlocked && r && currentMedia && !formatFailed && !audio.error
      && r.playing && now() >= r.anchorAtMs && expected(r) < r.clipEndMs
      && (state.room.phase !== 'COUNTDOWN' || audio.currentTime * 1000 < r.clipEndMs);
  }
  function align() {
    const r = state?.round;
    if (!connected() || !leader || now() >= clientLeaseUntil || !r || !currentMedia || formatFailed || audio.error) { cancelOutputCheck(); resetProgress(); audio.pause(); return; }
    if (gestureBlocked && performance.now() >= nextAutoplayAttemptAt) gestureBlocked = false;
    if (outputCheck) return;
    if (!userPaused && !gestureBlocked && checkedOutput !== outputKey() && (state.room.phase === 'PREPARING' || state.room.phase === 'COUNTDOWN' && now() < r.anchorAtMs)) { checkOutput(); return; }
    const target = expected(r) / 1000;
    if (!(state.room.phase === 'COUNTDOWN' && countdownStarted) && audio.readyState >= 1 && !audio.seeking && !playPending && Math.abs(audio.currentTime - target) > .5 && performance.now() - lastSeek >= 2000) {
      try { resetProgress(); audio.currentTime = target; lastSeek = performance.now(); } catch { /* Seek becomes available after metadata. */ }
    }
    if (!wantsPlayback()) { resetProgress(); audio.pause(); return; }
    if (audio.paused && !playPending && audio.readyState >= 2 && !audio.seeking) {
      const attempt = { media: currentMedia, generation: lastGeneration, auth: authGeneration, gesture: playbackGesture };
      if (state.room.phase === 'COUNTDOWN') countdownStarted = true;
      playPending = attempt;
      audio.play().then(() => {
        if (!wantsPlayback()) audio.pause();
      }).catch(error => {
        if (playPending !== attempt || attempt.media !== currentMedia || attempt.generation !== lastGeneration || attempt.auth !== authGeneration) return;
        if (error.name === 'NotAllowedError') { gestureBlocked = attempt.gesture === playbackGesture; checkedOutput = ''; nextAutoplayAttemptAt = performance.now() + 15000; audio.pause(); }
      }).finally(() => {
        if (playPending === attempt) {
          playPending = null;
          // A page gesture may precede an older play() promise's rejection.
          // Retry it once with current activation, then honor any new denial.
          if (attempt.gesture !== playbackGesture && !gestureBlocked) align();
        }
        paint();
      });
    }
  }
  function paint() {
    if (!authValid) return;
    const healthy = connected();
    $('connection').classList.toggle('connected', healthy); $('connection-text').textContent = healthy ? '연결됨' : '연결 대기';
    const r = state?.round; const s = status();
    const names = { CONNECTED: '대기 중', NEEDS_GESTURE: '자동 재생 차단됨', LOADING: '대기 중', PREPARED: '대기 중', PLAYING: '재생 중', PAUSED: '일시 정지', BUFFERING: '버퍼링 중', FAILED: '오디오 오류' };
    setText('audio-status', !healthy ? '연결 대기' : !leader ? '다른 창에서 재생 중' : names[s]);
    $('playback').hidden = !authValid || !leader || !r || !currentMedia;
    const playbackLabel = userPaused || gestureBlocked || audio.paused && !playPending ? '재생' : '일시 정지';
    setText('playback', playbackLabel); $('playback').setAttribute('aria-label', playbackLabel);
    if (r) {
      const progress = Math.max(0, Math.min(r.clipEndMs - r.clipStartMs, expected(r) - r.clipStartMs));
      const rate = playbackRate(r);
      $('progress').max = (r.clipEndMs - r.clipStartMs) / rate; $('progress').value = progress / rate;
      $('elapsed').textContent = format(progress / rate); $('remaining').textContent = format(Math.ceil((r.clipEndMs - r.clipStartMs - progress) / rate));
      $('countdown').textContent = !gestureBlocked && checkedOutput === outputKey() && r.playing && now() < r.anchorAtMs ? String(Math.ceil((r.anchorAtMs - now()) / 1000)) : '';
    } else { $('progress').value = 0; $('elapsed').textContent = '0:00'; $('remaining').textContent = '—'; $('countdown').textContent = ''; }
    void report(s);
  }
  function refreshState() {
    if (!authValid || refreshing) return refreshing;
    const generation = authGeneration;
    lastPoll = performance.now();
    refreshing = request('/api/v1/state').then(next => {
      if (authValid && generation === authGeneration) { apply(next); void report(); }
    }).catch(() => {}).finally(() => { refreshing = null; });
    return refreshing;
  }
  function connect() {
    clearTimeout(reconnectTimer); stream?.close(); const generation = ++connectionGeneration;
    stream = new EventSource('/api/v1/events');
    stream.addEventListener('state', event => { if (generation !== connectionGeneration) return; try { apply(JSON.parse(event.data)); retries = 0; } catch { message('상태를 다시 확인하는 중'); } });
    stream.addEventListener('lease', event => { if (generation !== connectionGeneration) return; try { const data = JSON.parse(event.data); authority = data.authorityLeaseExpiresAtMs; lastControl = performance.now(); if ($('message').textContent === errors.PLUGIN_OFFLINE) message(''); align(); paint(); } catch { /* The next snapshot repairs control state. */ } });
    stream.addEventListener('offline', () => { if (generation === connectionGeneration) pluginOffline(); });
    stream.addEventListener('revoked', () => { if (generation === connectionGeneration) revoke('SESSION_REVOKED'); });
    stream.onerror = () => {
      if (generation !== connectionGeneration) return; stream.close();
      if (!authValid) return;
      const delay = Math.min(15000, 1000 * 2 ** Math.min(retries++, 4)) * (.8 + Math.random() * .4);
      reconnectTimer = setTimeout(async () => {
        if (!authValid || generation !== connectionGeneration) return;
        await refreshState();
        if (authValid && generation === connectionGeneration) connect();
      }, delay);
    };
  }
  async function boot() {
    const generation = ++authGeneration;
    clientLeaseUntil = 0;
    try {
      const data = await request('/api/v1/me'); if (generation !== authGeneration) return; csrf = data.csrfToken; authValid = true;
      if (boundPlayer && data.me.uuid !== boundPlayer || boundCsrf && csrf !== boundCsrf) { revoke('SESSION_CHANGED'); return; }
      boundPlayer = data.me.uuid; boundCsrf = csrf; $('logout').disabled = false;
      $('nickname').textContent = data.me.nickname; avatar($('avatar'), data.me.textureHash);
      await calibrate(); const next = await request('/api/v1/state'); if (generation !== authGeneration) return; apply(next); connect(); void report();
    } catch (error) { if (error.status !== 401) { message('연결을 확인할 수 없습니다'); $('audio-status').textContent = '연결 대기'; } }
  }
  $('playback').addEventListener('click', event => {
    if (userPaused || gestureBlocked || audio.paused && !playPending) { userPaused = false; gestureBlocked = false; if (event.isTrusted) playbackGesture++; align(); }
    else { userPaused = true; playPending = null; cancelOutputCheck(); audio.pause(); }
    paint();
  });
  const retryGesture = event => {
    if (!event.isTrusted || event.repeat || userPaused || event.target?.closest?.('#playback, #takeover, #logout')) return;
    if (event.type === 'keydown' && (event.ctrlKey || event.metaKey || event.altKey || ['Shift', 'Control', 'Alt', 'Meta', 'Tab'].includes(event.key))) return;
    playbackGesture++; gestureBlocked = false; align(); paint();
  };
  for (const event of ['pointerup', 'click', 'keydown']) document.addEventListener(event, retryGesture);
  $('takeover').addEventListener('click', async event => { leader = true; takeover = true; clientLeaseUntil = 0; gestureBlocked = false; if (event.isTrusted) playbackGesture++; await report(); if (leader && authValid && now() < clientLeaseUntil) { $('takeover').hidden = true; align(); paint(); } });
  $('volume').addEventListener('input', () => { volume = Number($('volume').value); const output = outputCheck || audio; output.volume = volume / 100; output.muted = volume === 0; $('volume-value').value = String(volume); localStorage.setItem('noqui-volume', String(volume)); $('mute').setAttribute('aria-pressed', String(output.muted)); });
  $('mute').addEventListener('click', () => { const output = outputCheck || audio; output.muted = !output.muted; $('mute').textContent = output.muted ? '음소거' : '소리'; $('mute').setAttribute('aria-pressed', String(output.muted)); });
  $('reconnect').addEventListener('click', () => { if (authValid) { void refreshState(); connect(); } else void boot(); });
  $('logout').addEventListener('click', async () => { if (!authValid) return; try { await request('/api/v1/auth/logout', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Noqui-CSRF': csrf }, body: '{}' }); } catch { /* A replaced login is already stopped by request(). */ } finally { if (authValid) revoke('SESSION_REVOKED'); } });
  for (const event of ['loadedmetadata', 'canplay', 'seeking', 'seeked', 'playing', 'timeupdate', 'pause', 'waiting', 'ended', 'error']) audio.addEventListener(event, () => {
    if (event === 'error' && outputCheck) formatFailed = true;
    // Let a completed seek play before chasing the clock again, even if loading took longer than the correction interval.
    if (event === 'seeked') lastSeek = performance.now();
    if (['seeking', 'seeked', 'pause', 'waiting', 'ended', 'error'].includes(event)) resetProgress();
    align(); paint(); if (event === 'error' && currentMedia) message('오디오를 불러올 수 없습니다');
  });
  document.addEventListener('visibilitychange', () => { if (!document.hidden && authValid) { void refreshState(); void report(); void calibrate().catch(() => {}); } });
  window.addEventListener('storage', event => { if (event.key === 'noqui-session-change' && event.newValue) revoke('SESSION_CHANGED'); });
  window.addEventListener('online', () => { if (authValid) { connect(); void calibrate().catch(() => {}); } });
  window.addEventListener('offline', () => paint());
  setInterval(() => { align(); paint(); if (authValid && (stream?.readyState !== EventSource.OPEN || performance.now() - lastControl >= 10000) && performance.now() - lastPoll >= 2000) { void refreshState(); } }, 250);
  setInterval(() => void report(), 15000);
  void boot();
})();
