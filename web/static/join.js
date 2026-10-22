'use strict';
(() => {
  const token = new URLSearchParams(location.hash.slice(1)).get('t');
  history.replaceState(null, '', '/join');
  const status = document.getElementById('join-status');
  const fail = () => { status.textContent = '연결이 만료되었습니다'; document.getElementById('join-back').hidden = false; };
  if (!token || !/^[A-Za-z0-9_-]{43}$/.test(token)) { fail(); return; }
  fetch('/api/v1/auth/redeem', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }), cache: 'no-store' })
    .then(response => { if (!response.ok) throw new Error('JOIN_INVALID'); location.replace('/'); })
    .catch(fail);
})();
