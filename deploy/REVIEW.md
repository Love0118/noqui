# Noqui operations

## Architecture

The website is **https://noqui.danta.qzz.io**, using DNS-only A record
168.110.98.171 and ordinary HTTPS without a port suffix. nginx terminates TLS on
443 and proxies the frontend, API, event stream and authenticated Ogg Opus media
to **127.0.0.1:8787**. The plugin's signed bridge is **127.0.0.1:8788** and is
never proxied. Bind addresses and the public origin are compiled into the Rust
startup code. There is one ingest worker; there is no worker-count environment
option. The service/environment examples match `noqui/web/src/main.rs`.

Minecraft remains on its existing address and `mud.danta.qzz.io`. Keep
`/etc/haproxy/haproxy.cfg`, the chat-mirror service/certificate, and the existing
nginx resource-pack vhost unchanged. No HAProxy SNI rule is used for this site.

Public TCP80 and TCP443 must reach this host through **both UFW and OCI**.
The operator corrected the OCI destination-port rules for 80/443, and an
external HTTP ACME challenge fetch succeeded. nginx TLS is installed with a
trusted Let's Encrypt YE2 certificate for `noqui.danta.qzz.io`, expiring
**2027-01-20**. The renewal timer is enabled, and the renewal dry-run with its
deploy hook passed. Keep port80 available for ACME renewal and HTTPS redirects.
Cloudflare Origin CA certificates are unsuitable for this DNS-only browser
origin; retain the publicly trusted ACME certificate.

## Verified deployment, 2026-10-22

The operator verified all 16 Rust release-source hashes and **20 Rust tests**
on Ubuntu ARM64. The installed release is
`/opt/noqui/releases/20261022-92fd2080`, selected by `/opt/noqui/current`.
`noqui-web.service` is active, public `/healthz` responds successfully, and both
8787 and 8788 listen on loopback only. `/etc/noqui/noqui.env` matches the
environment example; `/etc/noqui/bridge.key` contains 32 raw bytes.

Actual YouTube ingestion of `jNQXAC9IVRw` produced **19.019 seconds**, **117,141
bytes**, stereo Opus at **48 kHz**, averaging **49.273 kbps including overhead**.
The browser decoded the audio and its playhead advanced normally with
`readyState=4` and no playback errors. The deployed encoding profile is
**`opus-48-clip115-v1`**. Cookie contents were never read or logged during these
checks. These are operator-reported deployment results; repeat the relevant
checks after future releases.

## Minecraft deployment, 2026-10-23 (KST)

Deployment followed the human's explicit instruction to restart and apply the
release. The implementation release is `beta`
`a055d7502cba9dd9c3208e8491cd1e9f14462103`; GitHub CI run **39803426324**
completed with all three jobs successful.

Paper completed a graceful shutdown and saved its worlds at **00:21:35 KST**.
The existing `KillSignal=SIGINT` caused exit130 to be classified as failed by
systemd even though MainPID was0. The operator checked the complete clean
world-shutdown log and MainPID0, ran `systemctl reset-failed
mc-luck-defense.service`, then verified inactive before taking the backup.

The full stopped backup is
`/home/ubuntu/mc-luck-defense-deploy/noqui-backup-20261022T152219Z/server.tar`.
Its SHA256 verification and `tar --compare` both passed; the numerical backup
digest is not recorded here. The original runtime pointer was backed up outside
`plugins`. Installed artifacts:

| Artifact | SHA256 |
| --- | --- |
| Noqui JAR | `7320549df50a5b445cccb1d0d837c0bd6a6a2cdd8874a30becdb80ea7078a8e8` |
| MCLuckDefense JAR | `5fbaccf2c097a914eb03fe2f8cc4aae9337de85a22f4ae347d1cad9c552467bc` |

Startup explicitly confirmed **loader1.0.25 / game1.0.25**. Via JARs and their
configuration directories were quarantined under the backup's `via-quarantine`;
the final `plugins` inventory matching `*via*` was empty. The frozen world tar
was imported, and Paper migrated the cabin to
`world/dimensions/minecraft/noqui_cabin`. Startup confirmed the world loaded
and Noqui enabled after spawn-floor and border checks.

Minecraft was active with PID **302075**, with startup `Done` at **00:23:01 KST**;
26.3/protocol777 status was verified. The quiz remains **stopped**
(`quiz-active=false`); an OP can enable it with `/noqui start`.
The Rust HTTPS health check remained successful after deployment. These are
operator-observed snapshots, not permanent service-state guarantees.

## Artifacts and state

- Rust binary and `yt-dlp-private-cookies.sh`: versioned
  `/opt/noqui/releases/RELEASE/`, selected by `/opt/noqui/current`.
- Configuration: `/etc/noqui/noqui.env`, root:ubuntu 0640. The example contains
  paths only. Bridge secret: `/etc/noqui/bridge.key`, exactly **32 raw bytes**,
  root:ubuntu 0640. Preserve the existing secret on updates.
- Use those same raw bytes in the plugin's configured bridge secret file, and
  `key-id: primary` on both sides. Do not confuse a 64-character hex string with
  the 32-byte file the Rust service requires. Never print either representation.
- Mutable state: `/var/lib/noqui`, owned by ubuntu, 0700; media SQLite, cached
  media and temporary ingest files. Keep it outside versioned releases and back
  it up only with `noqui-web.service` stopped or using a coherent SQLite backup.
- Minecraft: `/home/ubuntu/mc-luck-defense`, `mc-luck-defense.service`.
  Existing worlds, user settings and plugin data remain in this tree.
- The original ZIP and map outputs are ignored local assets, not Git content.

`noqui-web.service.example` limits the entire process group, including Rust,
yt-dlp/Python, Node and FFmpeg: **CPUQuota=50%** of one CPU, **MemoryMax=768M**,
**TasksMax=128**, nice10 and low CPU/IO weight. A 512M limit can leave too little
headroom for these processes together; 768M is an initial bounded allocation,
not a measured peak. Watch OOM/retry/latency before increasing concurrency.
The shared YouTube cookie source is read-only under ProtectHome. The downloader
wrapper copies it to a private 0600 jar **for each yt-dlp invocation**, passes
only that jar to the real downloader, and removes it after success or failure.
No cookie contents belong in logs, the release package or HTTP responses.

Per-invocation downloader cookie changes are intentionally discarded with that
temporary jar. Persistent rotation comes from the existing four-hour
`mc-luck-defense-youtube-cookies.timer`, which opens the dedicated private
browser profile, validates an export with yt-dlp and atomically publishes it.
When installing a newly supplied Netscape jar, seed that same profile once so
the timer does not re-export the former login. Preserve its existing lock,
0600 file permissions and 0700 profile directory. See
[cookie refresh operations](../../docs/youtube-cookie-refresh.md) for the
verified operator outcome and limits; revoked upstream login credentials still
require human reauthentication.
The YouTube jar authenticates ingestion only. Cached Opus playback uses the
separate Noqui session and Minecraft authority leases; rotating the source jar
does not expire a browser's already-authenticated listening session.

Observed tools: configured yt-dlp
`/home/ubuntu/mc-luck-defense/tools/media-venv/bin/yt-dlp` 2026.08.19;
`/usr/bin/ffmpeg` 6.1.1 with libopus; `/usr/bin/ffprobe`; Node v24.14.1.
Avoid obsolete `/usr/bin/yt-dlp` 2024.04.09. Official build tools,
pkg-config, Certbot/nginx integration and **Rust 1.99.1** are installed. Actual
ingestion and browser decoding passed as recorded above; credentials and
upstream availability can still change, so repeat an authorized ingest check
when diagnosing later failures. These procedures do not repeat package
installation.

## HTTP bootstrap, certificate and HTTPS

The initial website setup below is complete on this host. Retain this procedure
for a fresh installation or recovery; do not replace working config, secrets
or certificates merely to repeat it.

Stage the reviewed release and its trusted SHA256SUMS outside the live plugin
and world directories. Verify every supplied artifact before installation;
stop if any expected file is missing, a checksum differs, or the manifest names
an absolute/traversing/symlinked path. Do not trust a manifest obtained from a
different build. Validate `Noqui.jar`, the Rust binary and the frozen world tar.
The world tar's only allowed regular files are the three paths listed below.
Do not extract arbitrary serverpack ZIPs onto the live server.

```bash
set -euo pipefail
release=/path/to/reviewed-release
(cd "$release" && sha256sum --strict -c SHA256SUMS)
```

Back up the existing nginx configuration. Install the HTTP-only bootstrap as
`/etc/nginx/sites-available/noqui` and symlink only that file into sites-enabled;
there must never be two enabled Noqui vhosts. Keep every existing vhost enabled.
The ACME webroot is `/var/lib/letsencrypt`; nginx must be able to read it.

```bash
set -euo pipefail
# Stop any temporary HTTP probe before nginx takes port80.
sudo install -d -m 0755 /var/lib/letsencrypt/.well-known/acme-challenge
sudo install -m 0644 "$release/nginx-noqui-http.conf.example" /etc/nginx/sites-available/noqui
sudo ln -s /etc/nginx/sites-available/noqui /etc/nginx/sites-enabled/noqui
sudo nginx -t
sudo systemctl reload nginx.service
```

If the symlink already exists, inspect its target rather than replacing it
blindly. Place a harmless challenge test file in the webroot and fetch it from
outside OCI over HTTP80 before proceeding. Check that `noqui.danta.qzz.io`
resolves to this origin; do not change the existing Minecraft/mud DNS records.
Use certonly/webroot so Certbot does not rewrite unrelated nginx vhosts.
The following interactive issuance obtains operator consent and account email:

```bash
set -euo pipefail
sudo certbot certonly --webroot -w /var/lib/letsencrypt   --cert-name noqui.danta.qzz.io -d noqui.danta.qzz.io
sudo install -m 0644 "$release/nginx-noqui.conf.example" /etc/nginx/sites-available/noqui
sudo nginx -t
sudo systemctl reload nginx.service
```

The final vhost expects `/etc/letsencrypt/live/noqui.danta.qzz.io/fullchain.pem`
and `privkey.pem`. Keep HTTP01 challenge routing in the final vhost. Install
`certbot-noqui-reload.sh` in Certbot's renewal-hooks/deploy directory and verify
`certbot renew --dry-run --run-deploy-hooks --cert-name noqui.danta.qzz.io`; a hook syntax check
alone does not prove live certificate renewal. Confirm the timer is enabled.
nginx buffering/cache is disabled to preserve server events and session-bound
media; Rust retains authorization and byte-range semantics. nginx access logs
omit query strings. No public location forwards to port8788.

Install the immutable release, wrapper executable, reviewed env file and
systemd unit. Set `/opt/noqui/current` to that version. Existing env/secrets
must be compared and preserved, not overwritten by examples on each update.
`sudo systemd-analyze verify /etc/systemd/system/noqui-web.service` and
`sudo nginx -t` must pass before starting/reloading. Start the Rust service
separately from Minecraft and verify the public and bridge bindings are both
loopback-only. Static public checks do not prove signed bridge authentication.

## Minecraft maintenance and Via removal

Before stopping Minecraft, query the live server's online count using the
Minecraft status protocol or the console `list` command. Record the count/time;
**defer by default when the count is nonzero**. Proceed with nonzero players
only when the human explicitly approves that maintenance timing; record the
authorization and observed count. A failed status query still requires deferral.
Immediately recheck before the stop. Also ensure apt-daily-upgrade/package
maintenance is not active.
Only the operator stops/starts the service; do not kill the Java PID because
Restart=always would relaunch it. The observed stop uses SIGINT and 120s timeout.

Before this deployment the base MCLuckDefense JAR header was **1.0.16**, while
the earlier repository baseline was **1.0.24**. Deployment now confirms
**loader1.0.25 / game1.0.25**. A base JAR header alone does not establish the
running module version: the loader can activate a different runtime artifact.
Before future updates, use `/mud version`, inspect
`plugins/MCLuckDefense/runtime/active.properties`, and read the selected cached
artifact's `mud-runtime.properties`. Keep the selected active/previous hashes,
base loader and runtime cache in the backup. Any future dependency-metadata
change must be made in source and reviewed; do not patch a built JAR ad hoc.

After verifying zero players or recording explicit human approval for the
nonzero-player maintenance window, stop and create a consistent full server
backup while the service stays inactive. Run these as the owner or with
appropriate privilege. Use a new backup directory outside the server tree:

```bash
set -euo pipefail
server=/home/ubuntu/mc-luck-defense
backup=/home/ubuntu/mc-luck-defense-deploy/noqui-BACKUP-ID
sudo systemctl stop mc-luck-defense.service
# If the stop is classified failed, pause here for the reviewed SIGINT130
# recovery below. Do not create the backup until inactive and MainPID0 pass.
test "$(systemctl show mc-luck-defense.service -p ActiveState --value)" = inactive
test "$(systemctl show mc-luck-defense.service -p MainPID --value)" = 0
test ! -e "$backup"
install -d -m 0700 "$backup"
tar -C "$server" -cpf "$backup/server.tar" .
(cd "$backup" && sha256sum server.tar > server.tar.sha256)
test "$(systemctl show mc-luck-defense.service -p ActiveState --value)" = inactive
tar -C "$server" --compare -f "$backup/server.tar"
sudo systemctl cat mc-luck-defense.service > "$backup/mc-luck-defense.service.txt"
printf '%s\n' "$server" > "$backup/source-root.txt"
```

For the known SIGINT130 classification only, inspect the complete shutdown
journal and confirm normal world-save completion, no remaining server process,
MainPID0 and exit130. Only after that log review may the following recovery
clear the classification; then resume the stopped-backup procedure above.
Never normalize an unexpected failed state or a still-running process. A
reset-failed command is not proof of a clean shutdown and does not replace the
backup checksum or `tar --compare` checks.

```bash
set -euo pipefail
test "$(systemctl show mc-luck-defense.service -p ActiveState --value)" = failed
test "$(systemctl show mc-luck-defense.service -p MainPID --value)" = 0
test "$(systemctl show mc-luck-defense.service -p ExecMainStatus --value)" = 130
# Run only after manually verifying the complete clean world-shutdown log.
sudo systemctl reset-failed mc-luck-defense.service
test "$(systemctl show mc-luck-defense.service -p ActiveState --value)" = inactive
test "$(systemctl show mc-luck-defense.service -p MainPID --value)" = 0
```

If any command fails, do not install or quarantine anything. Before the next
steps, validate the full backup checksum and compare it with the still-stopped
server. A tar listing or marker file alone is insufficient proof of a usable
backup. Do not restart another agent's server or bypass a failed comparison.

Inspect Via variants in `plugins/`, `plugins/update/` and `plugins/.paper-remapped/`.
Quarantine **JARs and configuration directories** outside plugins together, with
relative paths retained for rollback. The following runs only while Minecraft
is inactive and after a verified stopped backup. It is deliberately a manual
operator step, not an automatically executed deployer:

```bash
set -euo pipefail
test "$(systemctl show mc-luck-defense.service -p ActiveState --value)" = inactive
(cd "$backup" && sha256sum --strict -c server.tar.sha256)
tar -C "$server" --compare -f "$backup/server.tar"
quarantine="$backup/via-quarantine"
test ! -e "$quarantine"
install -d -m 0700 "$quarantine/plugins"
while IFS= read -r -d '' file; do
    relative=${file#"$server/"}
    install -d -m 0700 "$quarantine/$(dirname "$relative")"
    mv -- "$file" "$quarantine/$relative"
done < <(find "$server/plugins" -type f     \( -iname 'ViaVersion*.jar' -o -iname 'ViaBackwards*.jar' \) -print0)
for name in ViaVersion ViaBackwards; do
    if test -e "$server/plugins/$name"; then
        mv -- "$server/plugins/$name" "$quarantine/plugins/$name"
    fi
done
find "$server/plugins" -iname '*via*' -print
```

Review that final inventory for renamed JARs or remapped metadata/directories;
inspect JAR plugin descriptors where names are ambiguous. Quarantine any
confirmed remaining ViaVersion/ViaBackwards variants, including remapped
settings directories, preserving their relative paths. Never recursively delete
them. Do not move unrelated plugins based only on a `via` substring. Do not use
PlugMan or live reload for this removal. The quarantined settings must be
outside `plugins`, not left behind or erased.

The initial import and Via quarantine are complete on this host; do not repeat
them over the running world. For a fresh installation, refuse import if either
the root `noqui_cabin` or migrated `world/dimensions/minecraft/noqui_cabin`
already exists, including a symlink.
Extract only the verified tar with root `noqui_cabin/`; preserve `world`, every
existing world directory and every unrelated plugin setting. Install the
reviewed Noqui JAR only after backup, and preserve an existing Noqui config and
bridge secret if this is an update. Do not copy examples over user settings.

```bash
set -euo pipefail
test ! -e "$server/noqui_cabin" && test ! -L "$server/noqui_cabin"
test ! -e "$server/world/dimensions/minecraft/noqui_cabin"
test ! -L "$server/world/dimensions/minecraft/noqui_cabin"
tar --no-same-owner -xpf "$release/noqui-world.tar.gz" -C "$server"
# Install the reviewed Noqui JAR and reviewed per-server settings here.
sudo systemctl start mc-luck-defense.service
sudo journalctl -u mc-luck-defense.service --since '5 minutes ago' --no-pager
```

Verify Paper26.3-only login, Noqui enable, house spawn/border/protection, bridge
sync and existing game health after future changes. Preserve loader/runtime
selection and check compatibility before any further MCLuckDefense update.

## Frozen world reference

`noqui-world.tar.gz`: **805,981 bytes**, SHA256
`cef6dddcb91fbb918b65e304f0233351ff56962b1e89d9e27daa2dfee040cc93`.
Local source: `.runtime/noqui-reference/world/noqui`; archive root and plugin
world name: `noqui_cabin`. Manifest:
`.runtime/noqui-reference/world/extraction-manifest.json`.

| File under noqui_cabin/ | SHA256 |
| --- | --- |
| level.dat | 58e55d5b7e0a10195a4b4631e1f497abfefa381ce87e9639ba9156e0799758c1 |
| region/r.0.-1.mca | 265f989b17e9004921e5941e8a4f1b37762ce4d53035a2af035fc4100c21234e |
| region/r.1.-1.mca | 5ffff8469feaf195a8d75b3ada9a0b14c6b2ba2b31e2d802bfe126c1146e8342 |

Retained 256 chunks: X18..33, Z-20..-5; blocks X288..543, Z-320..-65.
Border center **416,-192**, size **250**. Spawn **411.5,11,-205.5**, yaw0.3003012,
pitch0. Six dangerous blocks and six legacy spawner records removed; 752
sanitized decorative block entities kept. No players, saved actors, scheduled
queues, datapacks or serverpack copied. Source1.21.8 DataVersion4440 was imported
and migrated by live26.3/DataVersion5023 on the deploy copy; active storage is
`world/dimensions/minecraft/noqui_cabin`. Keep the source unchanged; never
downgrade an upgraded world. The plugin must use its void generator and apply
world protection; a border alone does not prevent outside terrain generation.

## Verification and rollback

Verify trusted external TLS, HTTP redirect/ACME route, page/API/media same
origin, actual authorized Opus playback and byte ranges, cached second playback,
bridge rejection from the public website, and unchanged Minecraft/chat/pack
routes. Record versions, hashes and service PIDs. File/config syntax checks do
not establish browser playback or world-load success.

On failure, stop Noqui web and Minecraft explicitly. Preserve newly generated
state, restore only the reviewed plugin/runtime/config changes and all
quarantined Via JARs/settings to their original relative paths, and select the
previous Rust release. Restore the prior Noqui nginx vhost after nginx -t.
Do not touch HAProxy or the unrelated vhosts. Do not blindly restore a broad old
world backup over new player saves; assess the data-loss window and restore a
coherent stopped backup if world data itself needs rollback. Start Minecraft,
then verify its active module version and known game health.

References: [Certbot webroot](https://eff-certbot.readthedocs.io/en/stable/using.html#webroot),
[nginx proxy buffering](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering),
[Paper updates](https://docs.papermc.io/paper/updating/).
