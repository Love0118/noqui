# Automatic playback

Noqui attempts playback automatically, including in a background tab. It does not
require a consent button and does not use an autoplay-policy detection API to
skip the first attempt. The browser still controls whether audible autoplay is
allowed.

Before reporting `PREPARED`, the client plays `/playback-check.wav` unmuted on the
same audio element used for the quiz. This 100 ms PCM file contains silence, so
the check cannot reveal the answer. A fulfilled playback request, an active
element, the current browser lease, and buffered quiz media are required for
readiness. This detects an existing permission; it cannot grant one. The saved
volume and mute preference are restored before loading the quiz source. Probe
positions and buffers are never reported as quiz media telemetry.

`NotAllowedError` reports `NEEDS_GESTURE` and displays `자동 재생 차단됨`. Neither a
blocked nor an unresolved check reports `PREPARED` or `PLAYING`, and its local
countdown is hidden. A denied request can retry after 15 seconds without a page
gesture, so a browser permission change does not permanently latch the client
off. Existing trusted interactions can also retry. Explicit user pause remains
authoritative. `PLAYING` still requires natural progress of the actual quiz
track, with no pending play request or seek.

## Browser limits

- A fresh Chrome origin normally rejects first audible playback without prior
  site interaction or an applicable browser permission. Permissions-Policy does
  not override this rule. See [Chrome's autoplay policy](https://developer.chrome.com/blog/autoplay).
- Chrome's explicit per-site **Sound: Allow** exception permits automatic audio.
  This differs from the default global sound setting. See Chromium's
  [implementation](https://chromium.googlesource.com/chromium/src/+/main/chrome/browser/content_settings/sound_content_setting_observer.cc)
  and [browser tests](https://chromium.googlesource.com/chromium/src/+/main/chrome/browser/media/unified_autoplay_browsertest.cc).
- Chrome can defer a never-visible background document's first playback request
  even with that site exception. A pending promise is not playback readiness.
  Noqui waits for the browser rather than starting the round timer from buffering
  alone. OS suspension, discarded tabs, and other browser restrictions remain
  outside the website's control.

The native connection dialog separately retries after a two-second dismissal
delay while an active quiz has a fresh disconnected browser observation. It
defers to Noqui menus, submission forms, and inventories. Connection, leaving,
session replacement, and stopping the quiz suppress subsequent prompts. The
existing Paper dialog exit action handles both the Close button and Escape;
no additional global close listener is installed.

## Verification

Fast browser regressions:

```powershell
node --test noqui/web/tests/browser.cjs
cargo test --manifest-path noqui/web/Cargo.toml
```

Real-browser checks require Playwright on the Node module path and an installed
Chrome/Chromium executable:

```powershell
$env:NOQUI_BROWSER_EXECUTABLE = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
node noqui/web/tests/autoplay.cjs
```

This suite launches separate temporary profiles directly, with the browser's
normal security, autoplay policy, audio output, and background throttling. The
allowed profile configures only the fixture origin's Sound permission. It uses
no clicks, keyboard input, autoplay flags, microphone permission, or WebRTC.
CDP reads explicitly set `userGesture: false`. Playwright focus emulation is
disabled before the player document loads. The local fixture serves production
assets with the production CSP and synthetic tone tracks (PCM WAV data at the
normal authenticated media URL shape); it does not test server transcoding.
JSON evidence includes the production app hash, browser version, natural quiz
progress, mute/volume, visibility, and `userActivation.hasBeenActive`.

The suite distinguishes fresh-origin denial, allowed foreground consecutive
tracks, consecutive tracks backgrounded after first being shown, and a
never-visible background document waiting without false readiness. It opens
and closes only its isolated browser profiles and loopback fixture servers.

Deploy the Rust route, WAV asset, and client together; updating only `app.js`
would leave the readiness check unavailable.

### Verified on 2026-10-25

Chrome `157.0.8103.15`, production app SHA-256
`286a7c014a9fc79998296c90b19601fb6bfa250d2c5527945143c1d805232bbf`:

| Isolated profile / visibility | Result |
| --- | --- |
| Fresh default origin | `NEEDS_GESTURE`; no `PREPARED`, `PLAYING`, or countdown |
| Sound allowed for fixture origin, visible | Two unmuted tracks start and progress without website interaction |
| Sound allowed, shown once then hidden before track playback | Both consecutive tracks start and progress with `document.hidden === true` |
| Sound allowed, never-visible background document | First check remains pending for the 10-second observation; no false readiness or countdown |

Every real-browser case had `userActivation.hasBeenActive === false`. The final
four-case run passed. Raw JSON evidence is written to
`noqui/web/target/autoplay-browser-evidence.jsonl` by the local verification run.
The fast suite passed 37 browser tests; Rust passed 79 tests. Isolated Java
compilation and JUnit execution passed 23 focused `BrowserConnectionsTest` and
`NoquiDialogsTest` cases without running the shared Maven build. The existing
Paper registry deprecation warning remains unchanged.
