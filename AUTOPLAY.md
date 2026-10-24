# Connection and playback

The web page first shows a monochrome connection screen with one **연결하기**
button. `/join#t=…` redeems its one-use token and removes the fragment immediately;
visiting `/` checks the existing session. Neither path loads the player or reports
playback readiness before the button is pressed. Failed authentication keeps the
player hidden and the button disabled.

The click calls `audio.play()` synchronously on the preloaded, silent 100 ms
`/playback-check.wav`. After that request succeeds, the player script loads and a
650 ms waveform/fade/slide transition reveals the player. There is no navigation
and no replacement of the audio element. Reduced-motion preferences skip the
transition. Audio or script-load failures leave a retryable connection screen.
The player restores the saved volume and mute preference before quiz playback.

Before reporting `PREPARED`, the player still requires a valid browser lease,
buffered quiz media, and a successful output check on this same element. Probe
positions are never reported as quiz progress. `PLAYING` requires natural
progress of the real quiz track. Blocked or unresolved playback does not start
the local countdown or produce false readiness. Explicit pause remains active
across track changes. The existing disconnected native-dialog retry is retained.

Browser autoplay policy still applies. The connection click supplies the initial
interaction instead of relying on a previous Sound permission or engagement
history. A reload requires another connection click. OS suspension and discarded
tabs remain outside the website's control.

## Verification

```powershell
node --test noqui/web/tests/browser.cjs
cargo test --locked --manifest-path noqui/web/Cargo.toml
$env:NOQUI_BROWSER_EXECUTABLE = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
node noqui/web/tests/autoplay.cjs
```

The Chrome suite requires Playwright on `NODE_PATH`. It creates isolated temporary
profiles with default site permissions and default autoplay policy. Headless
Chrome avoids unrelated desktop window occlusion; tab visibility and background
throttling remain enabled. CDP evaluations explicitly use `userGesture: false`.
One real input click targets **연결하기**; there are no further clicks, autoplay
policy overrides, or preconfigured Sound permissions. Fixture audio consists of
synthetic WAV tones at authenticated media-shaped URLs, so this checks browser
playback rather than YouTube conversion or physical speaker output.

Verified on 2026-10-25 with Chrome `157.0.8103.15`:

| Case | Result |
| --- | --- |
| Fresh origin, no connection click | Player remains hidden; no readiness, countdown or playback reports |
| One connection click | Two consecutive unmuted tracks progress without another click |
| Background tab after connection | Both tracks progress while `document.hidden` remains true |
| Mobile viewport with reduced motion | Connection skips animation delay; both tracks progress |

The suite also checks that the original audio element survives the transition and
the token fragment is absent. Preview PNGs are written to
`noqui/web/target/connection-preview/`. Fast verification passed 39 browser tests
and 79 Rust tests. The Moundi build passed 551 tests, including the lobby-only
compass menu and rejection of hidden/stale mine actions. Both isolated Paper
boots passed with the built 1.0.33 plugin.

Static assets are embedded in the Rust executable: deploy HTML, CSS, join script,
player script and the silent WAV together by rebuilding the web service.
