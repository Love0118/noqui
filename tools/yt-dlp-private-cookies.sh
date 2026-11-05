#!/usr/bin/env bash
# Keep the configured shared cookie jar read-only, including yt-dlp's writeback.
set -euo pipefail
umask 077

real_ytdlp=${NOQUI_YT_DLP_REAL:-/opt/noqui/tools/yt-dlp-2026.10.26/bin/yt-dlp}
# The authenticated default can resolve web_embedded URLs that GVS rejects with
# HTTP 403. Use the same working client for metadata and resolved-JSON downloads.
# Requires yt-dlp >= 2026.10.21; deployment pins the tested 2026.10.26 release.
args=(--extractor-args 'youtube:player_client=tv_samsung' "$@")
cookie_dir=
cleanup() {
    if [[ -n "$cookie_dir" ]]; then
        rm -f -- "$cookie_dir/cookies.txt"
        rmdir -- "$cookie_dir"
    fi
}
trap cleanup EXIT

seen_cookie=false
for ((i = 0; i < ${#args[@]}; i++)); do
    source_cookie=
    case "${args[i]}" in
        --) break ;;
        --cookies)
            if (( i + 1 >= ${#args[@]} )); then
                printf '%s\n' 'Missing cookie file argument' >&2
                exit 64
            fi
            source_cookie=${args[i + 1]}
            ;;
        --cookies=*) source_cookie=${args[i]#--cookies=} ;;
        *) continue ;;
    esac
    if [[ "$seen_cookie" == true || ! -f "$source_cookie" || ! -r "$source_cookie" ]]; then
        printf '%s\n' 'Cookie file unavailable or specified more than once' >&2
        exit 64
    fi
    seen_cookie=true
    cookie_dir=$(mktemp -d "${TMPDIR:-/tmp}/noqui-cookies.XXXXXXXX")
    cp -- "$source_cookie" "$cookie_dir/cookies.txt"
    chmod 600 -- "$cookie_dir/cookies.txt"
    if [[ "${args[i]}" == --cookies ]]; then
        args[i + 1]=$cookie_dir/cookies.txt
        i=$((i + 1))
    else
        args[i]=--cookies=$cookie_dir/cookies.txt
    fi
done

# Do not exec: the EXIT trap must remove the private jar after this invocation.
"$real_ytdlp" "${args[@]}"
