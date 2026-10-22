# Noqui bridge v1

The Minecraft plugin owns the one shared quiz room, authenticated player identity, private answer catalogue, scoring and authoritative timeline. Rust prepares each unique YouTube ID once, serves cached Ogg Opus bytes, and publishes a safe browser projection. The browser never controls scoring or advances a round.

Website: `https://noqui.danta.qzz.io`. Minecraft remains `mud.danta.qzz.io`. Public HTTP binds only `127.0.0.1:8787`; trusted plugin HTTP binds only `127.0.0.1:8788`. The TLS proxy must never expose `/internal/` or the internal port. There is no CDN/provider layer.

## Internal authentication

All four internal routes require `Host: 127.0.0.1:8788`, `X-Noqui-Key-Id`, `X-Noqui-Timestamp` (Unix seconds), `X-Noqui-Nonce` (base64url, at least 16 random bytes) and `X-Noqui-Signature: v1=<lowercase hex HMAC-SHA256>`. The shared secret is exactly 32 raw bytes. Sign this UTF-8 text without a trailing newline:

```text
UPPERCASE_METHOD
EXACT_REQUEST_PATH
TIMESTAMP
NONCE
lowercaseHex(SHA256(exact request body bytes))
```

Queries are rejected. Empty GET body hashes the empty byte string. Clock skew is at most 30 seconds; nonces cannot repeat for 120 seconds. Retry with a new nonce/signature and unchanged idempotent body. Bodies are bounded to 64 KiB. JSON request objects reject unknown fields. IDs are canonical lower-case UUIDs; sequence/revision values are unsigned decimal strings without leading zeroes. Times and positions are integer milliseconds.

## Plugin routes

| Route | Request | Response |
|---|---|---|
| `POST /internal/v1/media/jobs` | `{schemaVersion:1,requestId,videoId}` | `202` pending, `200` cached READY; `{jobId,state,mediaId,profileVersion,durationMs,byteLength,mime,failureCode,retryable,retryAfterMs}` |
| `GET /internal/v1/media/jobs/{jobId}` | Empty body | Same job descriptor |
| `POST /internal/v1/sync` | Full snapshot below | `{acceptedSeq,backendEpoch,serverTimeMs,authorityLeaseMs:15000,browsers:[...]}` |
| `POST /internal/v1/join-tokens` | `{schemaVersion:1,requestId,instanceId,uuid,membershipId,roomId,tokenSha256}` | `201 {expiresAtMs,expiresInSeconds}` |

`videoId` is exactly 11 ASCII YouTube ID characters. The plugin parses the native dialog's HTTPS YouTube URL locally and retains title/tags/aliases privately. Rust accepts no source URL. Missing tools/source authentication and source/network failures become explicit FAILED jobs. A successful registration is not playable until READY. Poll pending jobs at least two seconds apart; failed retryable jobs need a new explicit request after the 30-second cooldown. Same request ID with changed video/token data returns `409 IDEMPOTENCY_CONFLICT`. Concurrent requests for the same video/profile share one job.

Snapshot shape (all listed fields are required; nullable fields accept JSON null):

```json
{
  "schemaVersion": 1,
  "instanceId": "<plugin lifetime UUID>",
  "seq": "1",
  "sentAtMs": 1792600000000,
  "roster": [{
    "uuid": "<authenticated Minecraft UUID>",
    "membershipId": "<current join/room generation UUID>",
    "nickname": "PlayerOne", "textureHash": null, "roomId": "<room UUID>"
  }],
  "rooms": [{
    "roomId": "<room UUID>", "roomRevision": "1", "phase": "PREPARING",
    "round": {
      "roundId": "<round UUID>", "number": 1, "revision": "1",
      "playbackGeneration": "<audio load generation UUID>", "mediaId": "<READY media UUID>",
      "clipStartMs": 0, "clipEndMs": 115000,
      "anchorPositionMs": 0, "anchorAtMs": 1792600003000, "playing": false
    },
    "reveal": null
  }]
}
```

Send every two seconds and immediately on membership/phase/timeline changes, using one asynchronous in-flight request and coalescing obsolete heartbeats. Keep arrays for wire compatibility; v1 accepts at most one room and 64 players. Omission revokes membership and its tokens/sessions. `membershipId` changes on a new actual join or room membership generation. Read UUID/name/skin hash from the authenticated PlayerProfile, never from browser claims/IP or a third-party identity lookup.

`seq` increases per full snapshot; duplicate identical seq/body is idempotent but does not renew the 15-second authority lease. Lower seq or changed content at the same seq fails. Room/round revisions cannot move backward or change their corresponding content at the same revision. Preserve `instanceId` across hot reload; a different instance waits for the old authority lease to expire. A backend restart returns a new `backendEpoch` and accepts the current full snapshot.

Phases: IDLE, WAITING_FOR_MEDIA, PREPARING, COUNTDOWN, PLAYING, PAUSED, REVEAL, ENDED. IDLE/ENDED can have null round; WAITING_FOR_MEDIA can have null round or null mediaId. PREPARING/COUNTDOWN/PLAYING/PAUSED require a READY asset and coherent bounds. Clips start at offset zero and last at most **115 seconds**, capped to actual audio length; positions are relative to the cached first-115-second file. No arbitrary source-offset feature is provided. COUNTDOWN schedules a future anchor, normally **three seconds** ahead. The browser stays paused until the anchor. PLAYING requires playing=true; idle/pending/preparing/paused/ended require false. REVEAL permits either true or false: a first correct answer can reveal while the original timer/audio continues. Stop/skip/cancel/recovery snapshots may move directly to IDLE/REVEAL/ENDED; there is no restrictive phase ladder. REVEAL→IDLE with null round clears the player for the next submitter.

Before REVEAL, `reveal` must be null and no title, tags, aliases, videoId or source URL may appear in the snapshot. At reveal it can be `{title,aliases:[],tags:[],youtubeUrl:"https://www.youtube.com/watch?v=<id>"}`. The Rust job registry's private source ID is never part of a public DTO. Current file URLs, document/MediaSession titles and file metadata do not contain the answer. Future audio is not preloaded.

Browser entries in the sync response contain `uuid,membershipId,online,leaseExpiresAtMs,clientId,roundId,roundRevision,playbackGeneration,status,positionMs,lastSeenAtMs`; absent session fields are null. Browser telemetry is advisory, never a scoring input. PREPARED must match the intended round revision/generation. Native logic should use a visible preparation timeout/skip decision rather than treating a disconnected browser as ready.

## Browser authentication and control

The plugin generates 32 random bytes, base64url encodes them without padding (43 characters), registers **SHA256 of the token's UTF-8 string**, and privately sends `https://noqui.danta.qzz.io/join#t=<token>`. New issuance revokes the older outstanding token; expiry is 60 seconds. Redemption consumes the token atomically and creates a new opaque session. A lost successful response requires a new in-game link.

| Route | Behavior |
|---|---|
| `POST /api/v1/auth/redeem` | `{token}`; exact Origin + JSON; returns `{me,csrfToken,sessionExpiresAtMs}` and a Secure/HttpOnly/SameSite=Strict/Path=/ `__Host-noqui` cookie |
| `GET /api/v1/me` | Same safe identity/CSRF/absolute expiry descriptor |
| `GET /api/v1/time` | `{receivedAtMs,sentAtMs}` for minimum-RTT clock estimation |
| `GET /api/v1/state` | Current room-only safe snapshot |
| `GET /api/v1/events` | SSE: immediate `state`, later state updates and five-second `lease`; `revoked` then close on expiry |
| `POST /api/v1/playback` | `{clientId,takeOver,roundId,roundRevision,playbackGeneration,status,positionMs,bufferedUntilMs,lastEventId}`; returns `{serverTimeMs,leaseExpiresAtMs,activeClientId,heartbeatAfterMs:15000}` |
| `POST /api/v1/auth/logout` | `{}`; revoke cookie/session/SSE |

All authenticated writes require the exact Origin `https://noqui.danta.qzz.io`, JSON and `X-Noqui-CSRF`. One session per UUID/current membership; new redemption replaces old login. Idle timeout is 30 minutes, absolute expiry eight hours. Membership/authority expiry revokes the session. IP addresses never choose identity. Browser ack interval is 15 seconds; online lease is 60 seconds. A second tab requires explicit takeover or lease expiry. Statuses: CONNECTED, NEEDS_GESTURE, LOADING, PREPARED, PLAYING, PAUSED, BUFFERING, FAILED. Stale-round readiness cannot prepare the current round.

Public state contains `eventId,serverTimeMs,authorityLeaseExpiresAtMs,me,room,round,media,reveal`; `room` has `roomId,revision,phase,players` and player entries have `uuid,nickname,textureHash,browserOnline`. `round` uses the snapshot round fields. `media` is `{mediaId,url:"/media/<sha256>.ogg",mime:"audio/ogg; codecs=opus",durationMs}` or null. `reveal` is null until accepted REVEAL. A new SSE connection always gets the current snapshot, so old/future events are not replayed. Disable proxy buffering and use timeouts longer than the heartbeat interval.

## Cached media and jobs

`GET/HEAD /media/{sha256}.ogg` requires a current session and that room's current asset. Hashes are exact lower-case SHA256 values registered READY, not authorization tokens. Single byte ranges, open/suffix ranges, HEAD, conditional ETag/Last-Modified, If-Range and 416 are supported. Unsupported multiple/malformed ranges fall back to full 200. Responses use `audio/ogg; codecs=opus`, Accept-Ranges, strong ETag, and `private, max-age=31536000, immutable`. Audio is never gzip/Brotli transformed. Streams use 64 KiB buffers, at most four concurrent streams/session and 64 total. Browser cache/downloaded bytes cannot be revoked; this is not DRM.

Profile `opus-48-clip115-v1`: ffmpeg libopus, nominal 48kbps stereo 48kHz constrained VBR, one conversion per video/profile, no video/artwork/answer tags. Encode/cache only the first 115 seconds (or the shorter source) with FFmpeg `-t 115`; output validation permits up to 115.05 seconds for container/codec padding. Full client read-ahead is therefore bounded to about **0.69 decimal MB** at the nominal rate instead of about 1.8MB for a 300-second file, before container/TLS/HTTP overhead and VBR variation. The rate remains about 0.36MB/minute/listener. Current Chrome/Firefox/Edge and Safari 18.4 on supported OS versions are candidates; the client checks Ogg Opus support and reports failures visibly. Explicit initial audio gesture is required; OS/background/autoplay restrictions still apply.

Dedupe and READY media selection are profile-specific. Older full-length profile files are not eligible for the clipped profile's playback; a new registration creates/reuses the clipped job. Job status reports its actual stored profileVersion, including for older job IDs. Older queued profiles are not processed by the current worker. Existing cache files are not automatically deleted.

Limits: one job, queue 20, private source 300s/25MiB, cached output 115.05s/25MiB, temporary workspace 64MiB, logs 1MiB/file, total attempt deadline 300s. Prefer `bestaudio[ext=webm][abr<=80]`, falling back to the existing WebM/M4A selection when no matching low-bitrate WebM source is available. The worker checks actual files while processes run, kills its process group/tree on timeout/cancellation, and uses a Unix file-size limit. yt-dlp gets only an internally constructed canonical YouTube URL, a YouTube-only extractor, finite network retries and optional local cookie path. ffmpeg/ffprobe accept local files and a file/pipe protocol whitelist; source containers are restricted. There is no arbitrary URL/proxy endpoint. External downloader traffic is a separate process: host-level private/link-local/metadata egress denial remains a deployment responsibility, since Rust cannot enforce every DNS/redirect chosen inside yt-dlp.

Cache budget is 2GiB plus the bounded temporary workspace. New work reserves space conservatively and fails DISK_FULL when full; it **does not automatically evict files** that a room/catalogue may select. Completed immutable files are renamed atomically before READY is committed. Restart marks interrupted jobs retryable FAILED and rejects missing READY files. Partial temp directories are discarded at startup. Operator cleanup must coordinate unused cache removal with the registry; there is no public delete/repair endpoint.

Errors are `{error:{code,message,retryable,retryAfterMs,requestId}}`, with 400 validation, 401 authentication, 403 Origin/CSRF/membership, 404 private missing resource, 409 revision/idempotency/active-client/not-ready, 413 body limit, 422 source/timeline/answer-boundary, 429 queue/rate and 503 authority/tool availability. HTTP body-limit extractor rejection can be plain 413. Retry safe reads/idempotent writes on transport/429/5xx with bounded backoff; never replay a token redemption. No raw secrets, tokens, cookies, private answers or downloader stderr are sent in errors or application logs.

## Runtime and checks

Build/test from `noqui/web`: `cargo build --release --locked`, `cargo test --locked`, `cargo clippy --all-targets -- -D warnings`, `node --check static/app.js`, `node --check static/join.js`.

Runtime environment:

| Variable | Value/default |
|---|---|
| `NOQUI_BRIDGE_SECRET_FILE` | Required file containing exactly 32 raw secret bytes, shared with the plugin |
| `NOQUI_KEY_ID` | `primary` |
| `NOQUI_DATA_DIR` | `data`; use a dedicated writable private directory |
| `NOQUI_YT_DLP` | `yt-dlp` |
| `NOQUI_FFMPEG` | `ffmpeg` with libopus |
| `NOQUI_FFPROBE` | `ffprobe` |
| `NOQUI_YOUTUBE_COOKIES_FILE` | Optional operator-selected local cookie file; never a browser/API input |

Node must be available for yt-dlp's JavaScript runtime. Missing/unusable external tools produce a visible FAILED ingest job, while the control site remains available. Ubuntu service resource/egress restrictions, TLS/DNS, credentials, Java integration and live rollout are outside this crate's ownership.
