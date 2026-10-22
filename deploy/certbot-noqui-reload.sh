#!/bin/sh
# Certbot deploy hook: reload nginx only after this certificate was renewed.
set -eu
if [ "${RENEWED_LINEAGE:-}" = /etc/letsencrypt/live/noqui.danta.qzz.io ]; then
    /usr/sbin/nginx -t
    /usr/bin/systemctl reload nginx.service
fi
