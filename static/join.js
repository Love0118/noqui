'use strict';
(() => {
  const fail = (text = '연결이 만료되었습니다') => {
    document.getElementById('track-title').textContent = text;
    document.getElementById('connection-text').textContent = '연결 끊김';
    document.getElementById('audio-status').textContent = '연결 대기';
    document.getElementById('phase-label').textContent = '';
    document.getElementById('logout').disabled = true;
    document.getElementById('reconnect').hidden = true;
  };
  const start = () => {
    const script = document.createElement('script'); script.src = '/app.js';
    script.onerror = () => fail('연결을 확인할 수 없습니다');
    document.head.append(script);
  };
  if (location.pathname !== '/join') { start(); return; }
  const token = new URLSearchParams(location.hash.slice(1)).get('t');
  history.replaceState(null, '', '/join');
  if (!token || !/^[A-Za-z0-9_-]{43}$/.test(token)) { fail(); return; }
  fetch('/api/v1/auth/redeem', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }), cache: 'no-store' })
    .then(async response => {
      if (!response.ok) throw new Error('JOIN_INVALID');
      const data = await response.json();
      try {
        sessionStorage.setItem('noqui-player', data.me.uuid);
        localStorage.setItem('noqui-session-change', crypto.randomUUID());
      } catch { /* Server revocation and CSRF checks also stop older tabs. */ }
      // Keep this document and its audio element: navigating discards its visibility and interaction history.
      history.replaceState(null, '', '/'); start();
    })
    .catch(() => fail());
})();
