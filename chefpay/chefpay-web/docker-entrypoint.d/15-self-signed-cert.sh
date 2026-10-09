#!/bin/sh
set -e

CERT_DIR=/etc/nginx/certs

if [ -s "$CERT_DIR/fullchain.pem" ] && [ -s "$CERT_DIR/privkey.pem" ]; then
    echo "[tls] Using existing certificate in $CERT_DIR"
    exit 0
fi

CN="${TLS_SERVER_NAME:-localhost}"
case "$CN" in
    *[!0-9.]*) SAN="DNS:$CN,DNS:localhost,IP:127.0.0.1" ;;
    *)         SAN="IP:$CN,DNS:localhost,IP:127.0.0.1" ;;
esac

echo "[tls] No certificate found - generating a SELF-SIGNED one for '$CN'"
openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
    -keyout "$CERT_DIR/privkey.pem" -out "$CERT_DIR/fullchain.pem" \
    -subj "/CN=$CN" -addext "subjectAltName=$SAN"