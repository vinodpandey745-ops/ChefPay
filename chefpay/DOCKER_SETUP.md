## Docker: Build, Publish and Deploy

ChefPay is built on a developer machine, pushed to Docker Hub, and run on a Hostinger VPS using only `docker compose`. Nothing is built on the server.

### Architecture

```
Internet ──443/80──▶ caddy ──▶ chefpay-web (nginx + React UI) ──▶ chefpay-server (Spring Boot) ──▶ postgres
                                   │                                                              ▲
                                   └── /api /ws /platform /webhooks /manager /admin               │
                                                                           postgres-backup ───────┘
```

| Service | Image | Purpose |
|---|---|---|
| `caddy` | `caddy:2-alpine` | Public entry point. Gets and renews the Let's Encrypt certificate automatically, redirects HTTP to HTTPS. |
| `chefpay-web` | `sandeep3001/chefpay-web` | nginx serving the React UI and proxying backend routes. Not published to the host. |
| `chefpay-server` | `sandeep3001/chefpay-server` | Spring Boot backend (REST, WebSocket). Not published to the host. |
| `postgres` | `postgres:16-alpine` | Database. Not published to the host. |
| `postgres-backup` | `prodrigestivill/postgres-backup-local:16` | Daily database backups (7 daily, 4 weekly, 3 monthly). |

`chefpay-core` and `chefpay-plugin-api` are libraries compiled into the server jar, and `chefpay-javafx` is a desktop client, so none of them has an image.

### Repository files

```
chefpay/
├── docker-compose.yml          # optional: build and test locally
├── docker-compose.server.yml   # production file, copied to the server as docker-compose.yml
├── .env.example
├── .dockerignore               # used by the server image build
├── chefpay-server/Dockerfile
└── chefpay-web/
    ├── Dockerfile
    ├── .dockerignore
    ├── nginx/
    │   ├── default.conf.template
    │   └── chefpay-locations.conf
    └── docker-entrypoint.d/15-self-signed-cert.sh
```

`15-self-signed-cert.sh` must use **LF** line endings, not CRLF, or the web container will not start.

---

## Part 1: Build and publish (on your computer)

Run everything from the repository root (the folder containing `pom.xml`). Requires Docker with BuildKit (default in current Docker).

```bash
docker login

docker build -f chefpay-server/Dockerfile -t sandeep3001/chefpay-server:1.0.0 .
docker build -t sandeep3001/chefpay-web:1.0.0 chefpay-web

docker push sandeep3001/chefpay-server:1.0.0
docker push sandeep3001/chefpay-web:1.0.0
```

- Replace `sandeep3001` with your Docker Hub username and use a new version tag for every release (`1.0.1`, `1.0.2`, ...).
- The first build takes roughly 5 to 15 minutes. Later builds use the cache.
- Check the server architecture with `uname -m`. For `x86_64` the commands above are enough. For `aarch64` (ARM), build for both architectures:

```bash
docker buildx build --platform linux/amd64,linux/arm64 -f chefpay-server/Dockerfile -t sandeep3001/chefpay-server:1.0.0 --push .
docker buildx build --platform linux/amd64,linux/arm64 -t sandeep3001/chefpay-web:1.0.0 --push chefpay-web
```

Optional build arguments for the server image:

```bash
docker build -f chefpay-server/Dockerfile --build-arg SKIP_TESTS=false -t sandeep3001/chefpay-server:1.0.0 .      # run unit tests
docker build -f chefpay-server/Dockerfile --build-arg INSTALL_OCR=false -t sandeep3001/chefpay-server:1.0.0 .     # no invoice OCR, smaller image
```

Use private Docker Hub repositories for commercial software. Never reuse a tag for different content.

### Optional: test locally before pushing

```bash
cp .env.example .env      # set CHEFPAY_SECURITY_JWT_SECRET and DB_PASSWORD
docker compose up -d --build
```

Open `https://localhost` (self-signed certificate, accept the browser warning) or `http://localhost`.

---

## Part 2: Deploy on the Hostinger VPS

The server only pulls and runs images. It needs Docker and two files. It does not need Java, Node, Maven or the source code.

### 2.1 Requirements

- A Hostinger **VPS** (shared or web hosting cannot run Docker).
- In hPanel: **VPS, Manage, Operating System**, choose the **Docker** template (Ubuntu with Docker). On another OS, install Docker with `curl -fsSL https://get.docker.com | sudo sh`.
- Docker Compose v2.20 or newer: `docker compose version`.

### 2.2 DNS

In hPanel, open your domain's DNS settings and create an **A record** for the name you will use (for example `pos`) pointing to the VPS IP address. Remove any conflicting old A record for the same name. Verify before continuing:

```bash
nslookup pos.yourdomain.com      # must return the VPS IP
```

### 2.3 Firewall

Allow only ports **22** (SSH), **80** and **443** (TCP, and UDP 443 for HTTP/3) in the Hostinger VPS firewall. Do not open 5432 or 8080.

Ports 80 and 443 must be reachable before the first start, because Let's Encrypt validates the domain through them.

### 2.4 Server files

Connect with `ssh root@YOUR_SERVER_IP`, create the folder, and upload the two files from your computer:

```bash
mkdir -p /opt/chefpay
```

```bash
# from your computer
scp docker-compose.server.yml root@YOUR_SERVER_IP:/opt/chefpay/docker-compose.yml
scp .env root@YOUR_SERVER_IP:/opt/chefpay/.env
```

#### `docker-compose.yml` (server)

```yaml
name: chefpay

services:
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    command: caddy reverse-proxy --from ${DOMAIN:?set DOMAIN in .env} --to chefpay-web:8080
    ports:
      - "80:80"
      - "443:443"
      - "443:443/udp"
    volumes:
      - caddy-data:/data        # certificates live here - never delete this volume
      - caddy-config:/config
    depends_on:
      chefpay-web:
        condition: service_healthy
    networks: [chefpay-net]

  chefpay-web:
    image: ${DOCKERHUB_USER:?set DOCKERHUB_USER}/chefpay-web:${CHEFPAY_VERSION:?set CHEFPAY_VERSION}
    restart: unless-stopped
    environment:
      CHEFPAY_SERVER_HOST: chefpay-server
      CHEFPAY_SERVER_PORT: "8080"
    depends_on:
      chefpay-server:
        condition: service_healthy
    networks: [chefpay-net]

  chefpay-server:
    image: ${DOCKERHUB_USER:?set DOCKERHUB_USER}/chefpay-server:${CHEFPAY_VERSION:?set CHEFPAY_VERSION}
    restart: unless-stopped
    environment:
      SPRING_PROFILES_ACTIVE: postgres
      CHEFPAY_DB_URL: jdbc:postgresql://postgres:5432/chefpay
      CHEFPAY_DB_USER: ${DB_USER:-chefpay}
      CHEFPAY_DB_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD}
      CHEFPAY_SECURITY_JWT_SECRET: ${CHEFPAY_SECURITY_JWT_SECRET:?set CHEFPAY_SECURITY_JWT_SECRET}
      CHEFPAY_PLATFORM_OWNER_KEY: ${CHEFPAY_PLATFORM_OWNER_KEY:-}
      CHEFPAY_WHATSAPP_WEBHOOK_SECRET: ${CHEFPAY_WHATSAPP_WEBHOOK_SECRET:-}
      CHEFPAY_RAZORPAY_KEY_ID: ${CHEFPAY_RAZORPAY_KEY_ID:-}
      CHEFPAY_RAZORPAY_KEY_SECRET: ${CHEFPAY_RAZORPAY_KEY_SECRET:-}
      CHEFPAY_RAZORPAY_WEBHOOK_SECRET: ${CHEFPAY_RAZORPAY_WEBHOOK_SECRET:-}
      CHEFPAY_LOG_FILE: /app/logs/chefpay-server.log
      SERVER_FORWARD_HEADERS_STRATEGY: framework
      JAVA_OPTS: ${JAVA_OPTS:--XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError}
      TZ: ${TZ:-UTC}
    volumes:
      - chefpay-logs:/app/logs
    depends_on:
      postgres:
        condition: service_healthy
    stop_grace_period: 30s
    networks: [chefpay-net]

  postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: chefpay
      POSTGRES_USER: ${DB_USER:-chefpay}
      POSTGRES_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD}
      TZ: ${TZ:-UTC}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d chefpay"]
      interval: 5s
      timeout: 5s
      retries: 10
    networks: [chefpay-net]

  postgres-backup:
    image: prodrigestivill/postgres-backup-local:16
    restart: unless-stopped
    environment:
      POSTGRES_HOST: postgres
      POSTGRES_DB: chefpay
      POSTGRES_USER: ${DB_USER:-chefpay}
      POSTGRES_PASSWORD: ${DB_PASSWORD:?set DB_PASSWORD}
      SCHEDULE: "@daily"
      BACKUP_KEEP_DAYS: 7
      BACKUP_KEEP_WEEKS: 4
      BACKUP_KEEP_MONTHS: 3
    volumes:
      - postgres-backups:/backups
    depends_on:
      postgres:
        condition: service_healthy
    networks: [chefpay-net]

volumes:
  caddy-data:
  caddy-config:
  chefpay-logs:
  postgres-data:
  postgres-backups:

networks:
  chefpay-net:
```

#### `.env` (server)

```
DOMAIN=pos.yourdomain.com
DOCKERHUB_USER=yourdockerhubname
CHEFPAY_VERSION=1.0.0

DB_USER=chefpay
DB_PASSWORD=<strong password>

# generate with: openssl rand -base64 48
CHEFPAY_SECURITY_JWT_SECRET=<generated value>
CHEFPAY_PLATFORM_OWNER_KEY=<long random key>

# optional integrations (leave blank to disable)
CHEFPAY_WHATSAPP_WEBHOOK_SECRET=
CHEFPAY_RAZORPAY_KEY_ID=
CHEFPAY_RAZORPAY_KEY_SECRET=
CHEFPAY_RAZORPAY_WEBHOOK_SECRET=

TZ=Asia/Kolkata
```

Never commit `.env`. Use a different JWT secret and DB password for every deployment.

### 2.5 Start

```bash
cd /opt/chefpay
docker login                 # only if the Docker Hub repositories are private
docker compose up -d
docker compose ps            # wait until chefpay-web, chefpay-server and postgres are healthy
docker compose logs caddy    # look for: certificate obtained successfully
```

The first start takes 1 to 2 minutes while the database migrations run. Then open `https://pos.yourdomain.com`.

### What is automatic

| Task | Handled by |
|---|---|
| HTTPS certificate and renewal | Caddy |
| HTTP to HTTPS redirect | Caddy |
| Restart after a crash or server reboot | `restart: unless-stopped` |
| Database schema migrations | Flyway, on application start |
| Daily database backups | `postgres-backup` |

---

## Part 3: Operations

### Release a new version

1. On your computer: build and push the new tags (Part 1).
2. On the server, edit `.env` and set `CHEFPAY_VERSION=1.0.1`.
3. Run:

```bash
cd /opt/chefpay
docker compose pull
docker compose up -d
```

Take a Hostinger snapshot before upgrading. To roll back, set `CHEFPAY_VERSION` to the old tag and repeat step 3. Note that database migrations are not reversed by a rollback.

### Useful commands

```bash
docker compose ps                         # status and health
docker compose logs -f chefpay-server     # application logs
docker compose logs -f caddy              # certificate / proxy logs
docker compose restart chefpay-server     # restart one service
docker compose down                       # stop everything, keep data
```

**Never run `docker compose down -v` on the server.** It deletes the database, the backups and the certificates.

### Backups

Backups are written daily to the `postgres-backups` volume. List them:

```bash
docker compose exec postgres-backup ls -R /backups
```

They live on the same server, so they protect against mistakes and container problems but not against losing the VPS. Copy them off the server regularly and also use Hostinger snapshots.

Restore (try it on a test server first):

```bash
docker compose stop chefpay-server
docker compose exec postgres psql -U chefpay -d postgres -c "DROP DATABASE chefpay;" -c "CREATE DATABASE chefpay OWNER chefpay;"
docker compose exec postgres-backup sh -c 'zcat /backups/last/chefpay-latest.sql.gz | PGPASSWORD=$POSTGRES_PASSWORD psql -h postgres -U chefpay -d chefpay'
docker compose start chefpay-server
```

Use a specific file from `/backups/daily/` instead of `last/` to restore an older state.

### Troubleshooting

| Problem | Fix |
|---|---|
| `set DOMAIN` / `set DB_PASSWORD` / `set CHEFPAY_SECURITY_JWT_SECRET` error | The variable is missing or empty in `/opt/chefpay/.env`. |
| Caddy cannot get a certificate | The A record is wrong or not yet propagated, or ports 80/443 are blocked in the Hostinger firewall. Fix, then `docker compose restart caddy`. Avoid repeated failed attempts, because Let's Encrypt rate-limits them. |
| `port is already allocated` on 80 or 443 | Another service uses the port. Check with `ss -tlnp \| grep -E ':80\|:443'`. |
| `chefpay-web` exits with "no such file or directory" | `15-self-signed-cert.sh` was built with CRLF line endings. Convert to LF, rebuild, push a new version. |
| `exec format error` | The image architecture does not match the server. Use the multi-architecture build in Part 1. |
| `pull access denied` | The repository is private: run `docker login` on the server, and check `DOCKERHUB_USER` and `CHEFPAY_VERSION`. |
| Server stays unhealthy | Wait 1 to 2 minutes on first start, then read `docker compose logs chefpay-server`. |

### Notes

- Run a single `chefpay-server` instance. The WebSocket broker is in-memory.
- Point Razorpay and WhatsApp webhooks at `https://pos.yourdomain.com/...`.
- The JavaFX POS client is a desktop application. Set its `chefpay-client.properties` to `pos.yourdomain.com` on port 443.