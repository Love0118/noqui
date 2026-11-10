# 2026-11-06 audio interval bypass

The temporary bypass was activated at 00:35 KST. Valid Ogg Opus inputs with a constant initial granule offset were being rejected by the in-memory interval renderer with HTTP 422. The observed failing input had a constant offset of 336 samples. Downloading and converting the source had already succeeded; this was a separate delivery failure.

`media_bypass.py` is the exact deployed helper. Nginx routes `/media/` to its loopback-only port 8793. It asks the existing Rust service to validate the incoming session and current room/media before opening the file, and checks the same round again before delivery. It serves the full current source with single byte-range support and `private, no-store`. The existing browser timeline still governs quiz playback. Future media, missing sessions and other rooms remain inaccessible. The bypass deliberately disables server-side interval clipping; it is temporary, and full-source delivery can transfer more data. Nonzero interval-start behavior needs review before this becomes a permanent implementation.

The helper runs as `ubuntu` under `noqui-media-bypass.service`, with a 128 MiB memory limit and 50% CPU quota. The running Minecraft PID 984 and Rust PID 1500263 were preserved. No Rust binary, JAR, playlist manifest, score, ticket, or quiz-control request was changed.

2026-11-10 follow-up: the live helper now runs as the dedicated `noqui` account with a read-only data mount and additional systemd restrictions. See [the service hardening record](../20261110-service-hardening.md) for the current ownership and deployment requirements. The activation script below is a historical record and must not be rerun to recreate the original account settings.

Initial activation skipped playback tests at the user's request. The user subsequently requested a live playback test. Over an approximately 18-second sample, existing browser reports showed 21 `PLAYING` reports, no `FAILED` reports, and positions from 10,466 to 27,187 ms. A read-only request through the public HTTPS media URL returned HTTP 200 and 1,449,190 bytes. FFmpeg decoded the complete response without error. A 65,536-byte range returned HTTP 206 and matched the full response prefix. The same URL without a session returned HTTP 401. Session values and request headers were not persisted. Actual speaker output was not monitored. See `verification.json` for the machine-readable result.

Server files:

- Helper: `/opt/noqui/hotfixes/audio-bypass-20261106/media_bypass.py`
- Unit: `/etc/systemd/system/noqui-media-bypass.service`
- Activation record and original nginx backup: `/home/ubuntu/mc-luck-defense-deploy/audio-playback-fix-20261106/`
- Website config: `/etc/nginx/sites-enabled/noqui`

`activate_bypass.py` records the one-time activation procedure, including the systemd unit and nginx location. Do not rerun it on an already activated server. To roll back, first remove only the temporary `/media/` location pointing to port 8793 from the nginx site, run `sudo nginx -t`, and reload nginx. Then run `sudo systemctl disable --now noqui-media-bypass.service`. The original nginx backup is available for reference; restoring the entire file could overwrite subsequent unrelated configuration changes. Rollback re-enables the old interval renderer and its known failure unless that renderer has first been repaired.

The candidate Rust granule-offset patch was not deployed; a copy is preserved only in the local ignored scratch directory for later work.
