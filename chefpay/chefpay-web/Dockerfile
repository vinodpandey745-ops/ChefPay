# syntax=docker/dockerfile:1.7
#
# chefpay-web image: the React/Vite UI served by nginx. nginx also reverse-proxies the backend
# routes (/api, /ws, /platform, /webhooks, /manager, /admin) to chefpay-server, so this is the
# single public entry point of the stack.
#
# Build context is just this folder:
#   docker build -t chefpay/chefpay-web:latest chefpay-web

# ---------------------------------------------------------------------------
# 1) Build the SPA
# ---------------------------------------------------------------------------
FROM node:22-slim AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# vite.config.ts normally writes into ../chefpay-server/src/main/resources/static;
# override the output dir so this image is self-contained.
RUN npm run build -- --outDir /out --emptyOutDir

# ---------------------------------------------------------------------------
# 2) Runtime: unprivileged nginx (listens on 8080, runs as non-root)
# ---------------------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.27-alpine

USER root
RUN apk add --no-cache openssl \
 && mkdir -p /etc/nginx/certs /etc/nginx/snippets \
 && chown -R 101:101 /etc/nginx/certs

ENV CHEFPAY_SERVER_HOST=chefpay-server \
    CHEFPAY_SERVER_PORT=8080 \
    TLS_SERVER_NAME=localhost \
    FORCE_HTTPS=false \
    HTTPS_PUBLIC_PORT=443

COPY --from=build /out /usr/share/nginx/html
COPY nginx/default.conf.template     /etc/nginx/templates/default.conf.template
COPY nginx/chefpay-locations.conf    /etc/nginx/snippets/chefpay-locations.conf
COPY --chmod=755 docker-entrypoint.d/15-self-signed-cert.sh /docker-entrypoint.d/15-self-signed-cert.sh

USER 101
EXPOSE 8080 8443
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/nginx-health || exit 1
