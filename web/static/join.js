'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const gate = $('connect-screen'), button = $('connect-button'), status = $('connect-status');
  const player = $('player-screen'), audio = $('audio');
  let ready = false, connecting = false;
  const fail = (text = '연결이 만료되었습니다') => {
    status.textContent = text;
    button.disabled = true;
    ready = false;
  };
  const reveal = () => {
    player.hidden = false;
    gate.classList.add('is-leaving');
    player.classList.add('is-entering');
    const finish = () => {
      gate.hidden = true;
      player.classList.remove('is-entering');
      $('track-title').focus({ preventScroll: true });
    };
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) finish();
    else setTimeout(finish, 700);
  };
  const retry = text => {
    audio.pause();
    connecting = false;
    button.disabled = false;
    status.textContent = text;
  };
  button.addEventListener('click', () => {
    if (!ready || connecting) return;
    connecting = true;
    button.disabled = true;
    status.textContent = '연결 중';
    // Keep play inside the trusted click and retain this element for later tracks.
    audio.volume = .65;
    audio.muted = false;
    let playback;
    try { playback = audio.play(); }
    catch { retry('다시 연결해 주세요'); return; }
    Promise.resolve(playback).then(() => {
      audio.pause();
      const script = document.createElement('script');
      script.src = '/app.js';
      script.onload = reveal;
      script.onerror = () => { script.remove(); retry('다시 연결해 주세요'); };
      document.head.append(script);
    }, () => retry('다시 연결해 주세요'));
  });
  const prepare = () => {
    audio.src = '/playback-check.wav';
    audio.preload = 'auto';
    audio.load();
    ready = true;
    button.disabled = false;
    status.textContent = '연결 준비 완료';
  };
  const join = location.pathname === '/join';
  const token = join ? new URLSearchParams(location.hash.slice(1)).get('t') : null;
  if (join) {
    history.replaceState(null, '', '/join');
    if (!token || !/^[A-Za-z0-9_-]{43}$/.test(token)) { fail(); return; }
  }
  fetch(join ? '/api/v1/auth/redeem' : '/api/v1/me', {
    method: join ? 'POST' : 'GET', credentials: 'same-origin', cache: 'no-store',
    ...(join ? { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }) } : {}),
  }).then(async response => {
    if (!response.ok) { fail(response.status === 401 ? '연결 링크가 필요합니다' : '연결을 확인할 수 없습니다'); return; }
    const data = await response.json();
    if (!data.me?.uuid) { fail(); return; }
    try {
      const previous = sessionStorage.getItem('noqui-player');
      if (!join && previous && previous !== data.me.uuid) { fail('새 연결 링크 필요'); return; }
      sessionStorage.setItem('noqui-player', data.me.uuid);
      if (join) localStorage.setItem('noqui-session-change', crypto.randomUUID());
    } catch { /* The player also validates the server identity and CSRF token. */ }
    if (join) history.replaceState(null, '', '/');
    prepare();
  }).catch(() => fail('연결을 확인할 수 없습니다'));
})();
