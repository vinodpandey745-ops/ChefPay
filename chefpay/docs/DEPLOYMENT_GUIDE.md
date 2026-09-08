# ChefPay Deployment Guide

Workspace setup, producing the deployable server JAR, database/scalability guidance, and a
step-by-step Oracle Cloud deployment for ChefPay (Spring Boot 3.3.4 / Java 21 backend + React/Vite
frontend, served as one process).

---

## ⚠️ Read this first: two things stated honestly, up front

### 1. The Maven build has not been compiler-verified in this authoring environment

This guide was written inside a sandboxed cloud environment with **no access to Maven Central**.
Attempting `mvn -q -pl chefpay-core,chefpay-server -am compile` here fails immediately with:

```
Could not transfer artifact ... from/to central (https://repo.maven.apache.org/maven2): 
Transfer failed for ... 403 Forbidden
```

That is a restriction of *this authoring sandbox only* — it is not a limitation of the ChefPay
project, Maven, or Oracle Cloud. Because of it, **`mvn package` has never actually been run
against this codebase during authoring.** Every backend change across all development rounds was
hand-verified by careful reading and exact signature matching, not compiled.

The commands in this guide are correct and standard for a Spring Boot Maven multi-module project.
But **you (or your developer, or the Oracle Cloud VM itself)** must be the one to actually run
`mvn package` for the very first time and confirm it succeeds — any normal developer machine, CI
runner, or cloud VM has ordinary internet access and Maven Central will work fine there. Budget for
the realistic possibility of a small, easily-fixed compile error surfacing on that first real
build, since nothing here has been compiler-checked.

### 2. There is currently no live/public URL for ChefPay

ChefPay has only ever been run inside this development sandbox via temporary local processes
(`npm run dev`, `vite preview` against a mock backend) for building and screenshotting — it has
never been deployed anywhere with a real, internet-reachable address. There is no URL to hand you
"for now." The only way to get a real URL is to actually deploy it (see the Oracle Cloud section
below), after which it will be:

- `http://<VM-public-IP>:8080/app/` immediately after deployment, or
- `https://<yourdomain.com>/app/` once you point a domain at it and add TLS (both covered below).

---

## Section 1 — Local Workspace Setup

### Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | 21 | matches `maven.compiler.release` in the root `pom.xml` |
| Maven | 3.9+ | multi-module reactor build |
| Node.js | 20+ (with npm) | frontend build |
| Database | none required for dev | SQLite is file-based and used automatically in the `dev` profile |
| Database | Postgres 14+ or MySQL 8+ | only needed if you run the `postgres` or `mysql` profile |

### Confirmed module layout

Root `pom.xml` (`com.chefpay:chefpay-parent`, packaging `pom`) declares four modules, in this
order:

```
chefpay-plugin-api
chefpay-core
chefpay-server
chefpay-javafx
```

- `spring-boot.version` = **3.3.4**
- Java target = **21** (`maven.compiler.source`/`target` = 21, plus `<release>21</release>` on
  `maven-compiler-plugin`)
- `chefpay-server` is the one deployable Spring Boot artifact (REST API + WebSocket/STOMP +
  security + Flyway). `chefpay-javafx` is an unrelated desktop POS client that also depends on
  `chefpay-core`/`chefpay-plugin-api` but is not part of the web deployment — you can ignore it for
  everything in this guide.

### Clone/unzip and build the backend modules

From the repo root:

```bash
mvn -pl chefpay-plugin-api,chefpay-core,chefpay-server -am install
```

`-pl` selects just those three modules (skipping `chefpay-javafx`, which needs the JavaFX SDK and
isn't needed for the server); `-am` ("also make") tells Maven to also build whatever upstream
modules those depend on, in the correct order — here that's a no-op beyond the three listed, since
`chefpay-plugin-api` has no further internal dependencies, but it's good practice to always include
it. Use `install` if `chefpay-server` will later need the two library modules resolved from the
local `~/.m2` repo; use `compile` for a quick syntax check only.

### Run the API locally (SQLite dev profile)

```bash
cd chefpay-server
mvn spring-boot:run
```

Confirmed from `application.yml`:
- Server listens on port **8080** (`server.port: 8080`).
- Default active profile is `dev`, which wires SQLite via
  `jdbc:sqlite:${CHEFPAY_DB_PATH:./data/chefpay.db}?busy_timeout=30000` — no env vars needed. The
  DB file is created automatically at `./data/chefpay.db` relative to the working directory the JAR
  is run from. Set `CHEFPAY_DB_PATH` to override that path.
- In `dev`, Hibernate manages the schema (`ddl-auto: update`) and Flyway is disabled entirely
  (`spring.flyway.enabled: false`).

### Frontend dev server (day-to-day frontend work)

```bash
cd chefpay-web
npm install
npm run dev
```

Confirmed from `chefpay-web/vite.config.ts` and `package.json`:
- Dev server runs on **port 5173**.
- It proxies `/api` and `/ws` to `http://localhost:8080` (`changeOrigin: true`, and `ws: true` for
  the `/ws` WebSocket endpoint) — so this only works meaningfully against a locally running
  `chefpay-server` (started as above).
- `vite preview` (port 4173) carries the same proxy config, for smoke-testing the actual production
  bundle without React's dev-mode double-render.

---

## Section 2 — Producing the deployable server JAR ("final web version")

### The key convention: the frontend builds directly into the backend's static resources

Confirmed in `chefpay-web/vite.config.ts`:

```ts
base: '/app/',
build: {
  outDir: '../chefpay-server/src/main/resources/static/app',
  emptyOutDir: true,
},
```

`npm run build` (which runs `tsc -b && vite build`) compiles the React app and writes it **directly
into** `chefpay-server/src/main/resources/static/app`, with `base: '/app/'` matching the
`/app`/`/app/**` `permitAll` mount point already configured in `SecurityConfig`. That means: once
built, Spring Boot serves the compiled frontend itself as a static resource — **one deployable
artifact, one process, one port, no CORS configuration needed in production** (CORS is currently
wide-open (`allowedOriginPatterns: "*"`) specifically to support the pre-build dev-server/proxy
setup and multiple LAN client types — same-origin serving in production makes that moot for the web
app, though the permissive CORS config itself still applies to any other client that calls the API
cross-origin).

### Exact build sequence

```bash
cd chefpay-web
npm install
npm run build

cd ../chefpay-server
mvn -pl ../chefpay-plugin-api,../chefpay-core,. -am clean package -DskipTests
```

**Recommended form instead:** run the Maven step from the **repo root**, not from
`chefpay-server/`:

```bash
mvn -pl chefpay-plugin-api,chefpay-core,chefpay-server -am clean package -DskipTests
```

This is the more reliable of the two equivalent forms. Reasons:
- `-pl` module paths are relative to wherever the reactor root is invoked from; run from the repo
  root they're just the plain module names (no `../` path juggling to get wrong).
- It explicitly excludes `chefpay-javafx` — running a bare `mvn clean package` from the root would
  build all four modules including the JavaFX desktop client, which pulls JavaFX SDK
  platform-specific artifacts and has its own `javafx-maven-plugin`/packaging concerns that are
  completely irrelevant to (and could needlessly fail) a headless server build.
- It's the same command a CI pipeline or your developer's machine would run — one line, no `cd`
  step in between.

Either form works as long as `chefpay-web/npm run build` has already been run first, since the
static assets must exist under `chefpay-server/src/main/resources/static/app` *before* the Maven
package step picks them up as classpath resources.

### The resulting JAR

Confirmed from `chefpay-server/pom.xml`:

```xml
<build>
    <finalName>chefpay-server</finalName>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <version>${spring-boot.version}</version>
            <executions>
                <execution>
                    <goals><goal>repackage</goal></goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

`spring-boot-maven-plugin`'s `repackage` goal is what turns the plain library JAR into an
executable "fat" JAR (bundling all dependencies + an embedded Tomcat + a bootstrap launcher). With
`finalName` pinned to `chefpay-server`, the output is:

```
chefpay-server/target/chefpay-server.jar
```

### Why `-DskipTests` is used here — and why it should come off

`-DskipTests` is used above only because there's no compiler/test-verified build in this authoring
sandbox — skipping tests here reduces the flag's meaning to "avoid discovering a compile-time
surprise inside a test module too." **Once your developer or CI gets a first successful
`mvn package` without `-DskipTests` and the test suite passes, remove that flag** and let tests run
as a normal part of every build going forward.

### Running the JAR

Dev/SQLite (no configuration needed, same as `spring-boot:run`):

```bash
java -jar chefpay-server.jar
```

Production, Postgres profile:

```bash
export CHEFPAY_DB_URL=jdbc:postgresql://<host>:5432/chefpay
export CHEFPAY_DB_USER=chefpay
export CHEFPAY_DB_PASSWORD=<real-password>
export CHEFPAY_SECURITY_JWT_SECRET=<real-random-secret>

java -jar chefpay-server.jar --spring.profiles.active=postgres
```

Confirmed env var names from `application.yml`: `CHEFPAY_DB_URL`, `CHEFPAY_DB_USER`,
`CHEFPAY_DB_PASSWORD`, `CHEFPAY_SECURITY_JWT_SECRET` (plus `CHEFPAY_DB_PATH`, used only by the
`dev`/SQLite profile).

**`CHEFPAY_SECURITY_JWT_SECRET` must be set to a real random secret in every non-local
deployment.** The default, insecure placeholder baked into `application.yml` is:

```
dev-only-insecure-secret-change-me-before-any-real-deployment
```

The application will run perfectly fine using that string, which is exactly the danger — anyone
who reads the (public/repo) source can forge a valid JWT against a server still using it. Generate
a real secret, e.g. `openssl rand -base64 48`, and set it as `CHEFPAY_SECURITY_JWT_SECRET` before
any deployment a real customer will touch.

---

## Section 3 — Choosing a database for production

Confirmed from the full `application.yml`, ChefPay supports exactly three Spring profiles:

| Profile | Database | Schema management | When it's the right choice |
|---|---|---|---|
| `dev` (default) | SQLite (`./data/chefpay.db` by default) | Hibernate `ddl-auto: update`; **Flyway disabled** | Local development, a demo, or a very small single-terminal / single-branch pilot |
| `postgres` | PostgreSQL 14+ | Hibernate `ddl-auto: validate`; **Flyway enabled**, `classpath:db/migration` | Any real production use, multi-branch, or more than one concurrent writer |
| `mysql` | MySQL 8+ | Same as `postgres` | Same as `postgres`, if MySQL is the client's preferred/managed DB |

### SQLite's documented concurrency caveat

`application.yml`'s own comments spell out the tradeoff already baked into this app:

- `busy_timeout=30000` is set on the SQLite JDBC URL specifically because a service method
  (`NumberGeneratorService`) opens a second DB connection in its own `REQUIRES_NEW` transaction
  while the caller's own connection/transaction is still open — without a busy timeout, that second
  writer would fail immediately with `SQLITE_BUSY` instead of waiting.
- WAL mode is **deliberately not used**, because this app's read-then-nested-write-then-outer-write
  pattern across two connections is incompatible with WAL's per-transaction MVCC snapshot (it would
  surface a different, non-retryable `SQLITE_BUSY_SNAPSHOT` error). The default rollback-journal
  mode is used instead, where a second writer just waits on the first writer's lock.

In short: SQLite here is workable for low concurrency (one or two terminals hitting the DB at a
time) but is explicitly *not* the target for any real multi-writer production load — that's what
the `postgres`/`mysql` profiles and Flyway migrations exist for.

### Switching profiles requires Flyway to actually run

`dev` never runs Flyway (`spring.flyway.enabled: false`) — schema is created/updated by Hibernate.
`postgres`/`mysql` both flip that to `enabled: true` with `hibernate.ddl-auto: validate` (Hibernate
only checks the schema matches; it never creates or alters it). That means the very first time you
run against Postgres/MySQL, Flyway must run all migrations from
`chefpay-server/src/main/resources/db/migration/` — confirmed present from `V1` through the highest
currently in the repo, **`V27__round18_category_hierarchy_and_merge.sql`** (27 migration files
total, `V1__phase1_schema.sql` … `V27__round18_category_hierarchy_and_merge.sql`). Flyway runs
these automatically on application startup against whichever DB `CHEFPAY_DB_URL` points at — there
is no separate manual migration step, but the target database/user in `CHEFPAY_DB_URL`/
`CHEFPAY_DB_USER`/`CHEFPAY_DB_PASSWORD` must exist and be reachable before the JAR starts.

---

## Section 4 — Scalability guide (bigger infrastructure for a big client)

### Database

Moving off SQLite to Postgres/MySQL is step one for any real scale-up (see Section 3). Connection
pooling is HikariCP (Spring Boot's default `DataSource` pool, no extra dependency needed) and can be
tuned with standard properties, e.g.:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
```

### Statelessness (the API layer scales horizontally)

Confirmed in `SecurityConfig.java`: `sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))`,
with a JWT bearer token validated per-request by `JwtAuthenticationFilter`/`JwtService`. Because
auth carries no server-side session state, plain REST/HTTP traffic can be load-balanced across
multiple `chefpay-server` instances with no session affinity required — any instance can validate
any request's token independently.

### The one caveat: WebSocket/STOMP needs sticky sessions (or a shared broker) to scale past one instance

Confirmed in `WebSocketConfig.java`: the app uses `@EnableWebSocketMessageBroker` with

```java
registry.enableSimpleBroker("/topic");
```

`enableSimpleBroker` is Spring's **built-in in-memory broker** — it only fans messages out to
clients connected to *that same JVM instance*. Today's config is a single-instance v1 setup: fine
for one running `chefpay-server` process, but it will **not** propagate a message published from
one instance to a client connected to a different instance. This matters for ChefPay because
real-time features (kitchen display updates, live order notifications over `/topic/notifications`
and friends) are all delivered this way.

To truly scale WebSocket/STOMP traffic across multiple app instances, you need one of:
1. **Sticky sessions** at the load balancer (route each client's WebSocket connection to the same
   backend instance every time) — the simpler option, works with zero code changes, but the
   in-memory broker still means a message published on instance A never reaches a client stuck on
   instance B, so it only really works if you also ensure the event *publishing* side reaches the
   right instance (in practice, this is a real limitation for multi-instance deployments, not just
   a load-balancer setting).
2. **Replace `enableSimpleBroker` with a real external STOMP relay** — Spring supports
   `enableStompBrokerRelay(...)` backed by a broker that speaks STOMP (e.g. RabbitMQ with its STOMP
   plugin), or a Redis-backed pub/sub bridge. This is the correct fix for true horizontal scaling
   and is the piece of extra infrastructure work needed to go beyond a single `chefpay-server`
   instance for real-time features. It is not implemented today — `WebSocketConfig.java` would need
   to be updated as part of that scale-up work.

### Static assets

Since the compiled frontend is served from the JAR's own static resources (`static/app`), a
high-traffic deployment could put a CDN or a reverse-proxy cache (Nginx, Cloudflare, etc.) in front
of the app to offload static asset traffic from the JVM. For a single-restaurant or single-chain
scale this is optional — the embedded Tomcat serving static files directly is not a bottleneck at
that volume.

### Images stored as base64-in-database

Confirmed by grep: multiple places store image data as base64 text directly in the database, most
notably `Restaurant.logoImageBase64` (`chefpay-core/src/main/java/com/chefpay/core/domain/Restaurant.java`):

```java
/** Base64-encoded logo image (whatever format the file was uploaded as - PNG/JPG), shown at
 * the top of the Dashboard screen and configurable from Settings. Stored as plain Base64 text ... */
private String logoImageBase64;
```

The same base64-in-DB pattern also appears around supplier invoice images (`SupplierInvoiceDtos`,
`SupplierInvoiceService`), AI menu import (`AiMenuImportDtos`), EOD/theme/menu/billing DTOs, and
`ZReportPdfBuilder`. This is fine at small scale but does not scale well once a client uploads many
or large images — every read/write of that row moves the full image payload through the database
and app memory. If a big client uploads many/large images, this should move to object storage
(e.g. **OCI Object Storage**, or any S3-compatible store), with the DB holding just a URL/key
instead of the encoded bytes. That is a real code change (not just infra), scoped to whichever of
the fields above the client actually stresses.

### Multi-branch scaling

The Organization/Branch/Terminal model already supports many branches under one organization
talking to one shared server (confirmed present as domain concepts, e.g.
`OrganizationTerminalIdentity`/`V26__round17_organization_terminal_identity.sql`). For a "big
client" with many branches, this mostly means **sizing the single server/DB bigger** (vertical
scaling) plus the horizontal-scaling notes above for the API layer — it is not an architectural
redesign.

### Oracle Cloud sizing suggestions

| Tier | App tier | Database | Notes |
|---|---|---|---|
| **Pilot / small** (1–2 branches) | Single small VM — e.g. `VM.Standard.E4.Flex`, 2 OCPU / 8–16 GB RAM (or the Always-Free `VM.Standard.E2.1.Micro` for a pure demo) | SQLite on the same box, or a small Postgres instance also on the same box | Simplest possible setup; fine while write concurrency stays low |
| **Mid-size** (multi-branch chain) | Slightly larger VM (e.g. `VM.Standard.E4.Flex`, 4 OCPU / 16–32 GB) | A managed **Oracle Autonomous Database** (Postgres-compatible workloads via ADB, or a dedicated Postgres/MySQL instance) kept **separate** from the app VM | Separating DB from app avoids resource contention and gives independent backup/HA for the data |
| **Large / enterprise** | Multiple app VM instances behind an **Oracle Cloud Load Balancer** | A managed HA database (Autonomous Database with HA, or a managed Postgres/MySQL cluster) | Requires the WebSocket sticky-session-or-shared-broker change described above; this is the tier where that work actually becomes necessary |

---

## Section 5 — Step-by-step Oracle Cloud deployment

### 1. Create a compute instance

In the Oracle Cloud console: **Compute → Instances → Create Instance**.
- Shape: `VM.Standard.E2.1.Micro` is in the **Always-Free** tier — fine for a demo or pilot. For
  real production traffic, pick a paid shape sized per Section 4 (e.g. `VM.Standard.E4.Flex`).
- Image: Oracle Linux (8/9) or Ubuntu (22.04/24.04) — either works; commands below give both.
- Add/select an SSH key pair so you can log in.
- Note the instance's **public IP address**.

### 2. Open the needed port — both cloud-level and OS-level

Oracle Cloud instances need the port opened in **two** places, not just one:

**a) Cloud-level: Security List or Network Security Group** (VCN → your subnet's Security List, or
the instance's attached NSG) — add an ingress rule:
- Source CIDR: `0.0.0.0/0` (or restrict to known IPs)
- Port: `8080` for a direct demo (no reverse proxy), or `80`/`443` if fronting with Nginx (step 7)

**b) OS-level firewall on the instance itself** — Oracle Linux images ship with `firewalld` enabled
by default; Ubuntu images typically don't enable `ufw` by default but check:

```bash
# Oracle Linux (firewalld)
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --reload

# Ubuntu (if ufw is active)
sudo ufw allow 8080/tcp
```

Both steps are required — the cloud security rule alone will not let traffic through if the OS
firewall is still blocking the port, and vice versa.

### 3. Install Java 21

```bash
# Oracle Linux / RHEL-family
sudo dnf install -y java-21-openjdk

# Ubuntu
sudo apt update && sudo apt install -y openjdk-21-jdk
```

Verify with `java -version` — it must report 21.

### 4. Copy the built JAR to the instance

Build it locally first (Section 2), then:

```bash
scp chefpay-server/target/chefpay-server.jar opc@<instance-public-ip>:/tmp/chefpay-server.jar
# (use ubuntu@... instead of opc@... for an Ubuntu image)

ssh opc@<instance-public-ip>
sudo mkdir -p /opt/chefpay
sudo mv /tmp/chefpay-server.jar /opt/chefpay/chefpay-server.jar
```

### 5. Set production configuration

Decide where the database lives: on the same VM (install Postgres/MySQL locally) or as a separate
managed instance (recommended for mid-size and up — see Section 4). Either way you need:

- `CHEFPAY_DB_URL`, `CHEFPAY_DB_USER`, `CHEFPAY_DB_PASSWORD` — pointing at that database
- `CHEFPAY_SECURITY_JWT_SECRET` — a real random secret (`openssl rand -base64 48`), never the
  `dev-only-insecure-secret-change-me-before-any-real-deployment` default

These are set as `Environment=` lines in the systemd unit below, not exported in a shell session,
so they persist across reboots and don't depend on an interactive login.

### 6. Run it as a systemd service

Create `/etc/systemd/system/chefpay.service`:

```ini
[Unit]
Description=ChefPay Server
After=network.target

[Service]
Type=simple
User=chefpay
WorkingDirectory=/opt/chefpay
ExecStart=/usr/bin/java -jar /opt/chefpay/chefpay-server.jar --spring.profiles.active=postgres
Restart=always
RestartSec=5

Environment=CHEFPAY_DB_URL=jdbc:postgresql://<db-host>:5432/chefpay
Environment=CHEFPAY_DB_USER=chefpay
Environment=CHEFPAY_DB_PASSWORD=<real-password>
Environment=CHEFPAY_SECURITY_JWT_SECRET=<real-random-secret>

[Install]
WantedBy=multi-user.target
```

(Create a dedicated `chefpay` system user first — `sudo useradd -r -s /sbin/nologin chefpay` — and
`chown` `/opt/chefpay` to it, rather than running the service as root or `opc`.)

Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now chefpay
sudo systemctl status chefpay      # confirm it's active (running)
journalctl -u chefpay -f           # tail logs / confirm Flyway migrations ran and startup succeeded
```

At this point the app is reachable at `http://<instance-public-ip>:8080/app/` — that is the "IP
demo" URL referenced up top.

### 7. (Optional) Nginx reverse proxy + Let's Encrypt TLS

For a clean HTTPS domain URL instead of `http://<ip>:8080`:

```bash
# Oracle Linux
sudo dnf install -y nginx certbot python3-certbot-nginx
# Ubuntu
sudo apt install -y nginx certbot python3-certbot-nginx
```

Nginx server block (e.g. `/etc/nginx/conf.d/chefpay.conf` or
`/etc/nginx/sites-available/chefpay`), proxying everything — including the WebSocket endpoint — to
the app on 8080:

```nginx
server {
    listen 80;
    server_name yourdomain.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

The `Upgrade`/`Connection: upgrade` headers matter here specifically because `/ws` is a WebSocket
endpoint, not just plain HTTP — without them the STOMP connection would fail behind the proxy.

```bash
sudo systemctl enable --now nginx
sudo certbot --nginx -d yourdomain.com
```

`certbot` obtains a Let's Encrypt certificate and rewrites the Nginx config to serve HTTPS on 443,
redirecting 80 → 443. Remember to open port 443 (and close/restrict direct 8080 access) in both the
Security List/NSG and the OS firewall, same as step 2.

### 8. Point a domain at the instance

In your DNS provider, add an **A record** for `yourdomain.com` (or a subdomain like
`pos.yourdomain.com`) pointing at the instance's public IP. Propagation can take minutes to a few
hours; `certbot --nginx` in step 7 requires this to already resolve correctly before it can issue a
certificate.

### Resulting URL, in each case

| Setup | Resulting URL |
|---|---|
| IP-only demo, no reverse proxy | `http://<instance-public-ip>:8080/app/` |
| Domain + Nginx + Let's Encrypt TLS | `https://pos.yourrestaurant.com/app/` (or whatever domain you point at it) |

Either way, the REST API and WebSocket endpoint live at the **same host and port** as the web app —
`/api/...` and `/ws` respectively — so nothing extra needs to be separately exposed or proxied; the
Nginx config above (or the direct `:8080` port for the IP-only case) already covers all three
(`/app`, `/api`, `/ws`) through the one listener.

---

## Section 5A — Upgrading a VM that's already running an older ChefPay build to this web version

If you already went through Section 5 once for an earlier build (for example, a pre-web-frontend
round of the server that a JavaFX desktop client was talking to) and it's currently working as a
`systemd` service on the VM, you do **not** need to redo the VM setup — no new Security List rule,
no new firewall rule, no new Java install. The web version is the *same* Spring Boot process on the
*same* port; the only difference is that its JAR now has a compiled React app embedded in it. This
section is the exact diff.

Do steps 1–4 on **your own computer** (the machine you're building on, not the VM) — the JAR has to
be assembled wherever the frontend gets built, because `npm run build`'s output must already be
sitting in `chefpay-server/src/main/resources/static/app` *before* `mvn package` runs, or the new JAR
won't contain the web app at all.

### 1. Build the frontend, then the JAR, locally

```
cd chefpay-web
npm install
npm run build
cd ..
mvn clean package -DskipTests
```

Confirm the frontend actually landed inside the server module before packaging — `npm run build`
should have populated `chefpay-server/src/main/resources/static/app/` (an `index.html`, an `assets/`
folder, etc.). `mvn clean package -DskipTests` run from the repo root builds all four modules via
the parent POM and produces the fat JAR at:

```
chefpay-server/target/chefpay-server.jar
```

(This is the same `mvn package`/`-DskipTests` caveat from the top of this guide: it's never been
compiler-verified in the authoring sandbox, so watch the build output for the first run.)

### 2. Sanity-check the JAR locally before touching the VM

```
java -jar chefpay-server/target/chefpay-server.jar
```

Open `http://localhost:8080/app/` in a browser — you should see the ChefPay login/terminal-setup
screen, not a blank page or a 404. If that works, the JAR is good to ship. Stop it (Ctrl+C) once
confirmed.

### 3. Back up the VM's current database before upgrading

Whatever profile the existing deployment uses:

- **SQLite** — copy the `.db` file the running service points at (check `CHEFPAY_DB_PATH`, or the
  default `./data/chefpay.db` relative to wherever it runs from): `cp data/chefpay.db data/chefpay.db.bak-preweb`.
- **Postgres** — `pg_dump -U chefpay chefpay > chefpay-preweb-backup.sql`.
- **MySQL** — `mysqldump -u chefpay -p chefpay > chefpay-preweb-backup.sql`.

This matters because the new JAR carries Round 17/18 schema changes (Organization/Branch/Terminal
identity, category hierarchy, etc.) that weren't in an older build. Against SQLite, Hibernate
`ddl-auto=update` adds the missing columns automatically on next boot; against Postgres/MySQL,
Flyway applies whatever migrations (`V1`…`V27`) the old deployment hadn't already run yet. Both are
additive, forward-only changes — but back up first regardless, as ordinary production hygiene
before any schema change.

### 4. Upload the new JAR and swap it in

```
scp chefpay-server/target/chefpay-server.jar opc@<instance-public-ip>:/tmp/chefpay-server.jar
```

(use `ubuntu@` instead of `opc@` if the VM is an Ubuntu image rather than Oracle Linux)

Then, on the VM itself:

```
sudo systemctl stop chefpay
sudo cp /opt/chefpay/chefpay-server.jar /opt/chefpay/chefpay-server.jar.bak-nonweb
sudo mv /tmp/chefpay-server.jar /opt/chefpay/chefpay-server.jar
sudo systemctl start chefpay
sudo systemctl status chefpay          # confirm "active (running)"
sudo journalctl -u chefpay -f          # tail the log; watch for "Started ChefPayServerApplication"
                                        # and, on Postgres/MySQL, Flyway lines applying V1..V27
                                        # cleanly with no errors
```

Adjust the two `/opt/chefpay/...` paths above if the existing unit's `ExecStart=` points somewhere
else — check with `cat /etc/systemd/system/chefpay.service` (or whatever the unit is named; `sudo
systemctl list-units --type=service | grep -i chefpay` if unsure of the exact name) first, and reuse
its actual `ExecStart` path and every `Environment=` line as-is. You are only replacing the JAR file
in place — the unit file itself, its env vars (DB URL/user/password, JWT secret, active profile),
and the port it runs on all stay exactly what already works today.

### 5. Open the web app

No new port-opening step is needed — the existing Security List/NSG rule and OS firewall rule that
already let the old (API-only) build through on its port cover this build too, since it's the same
process on the same port serving one more thing (`/app/*`) alongside what it already served
(`/api/*`, `/ws`). Browse to:

```
http://<instance-public-ip>:8080/app/
```

— note the trailing `/app/`; the frontend is served under that base path, so `http://<ip>:8080/`
alone won't show it. Log in with whatever staff account already exists in that database (or the
default `admin` / `admin123`, PIN `1234`, if this is the very first login — change it immediately
per the checklist below, since this account is now reachable from the public internet).

If anything looks wrong after the swap — blank page at `/app/`, or the app loads but API calls fail
— the two most common causes are (a) the JAR was built without `npm run build` having populated
`static/app` first (repeat step 1), or (b) the unit's `Environment=` block is missing a variable the
new build now expects (compare against the full `application.yml` in `TECHNICAL_GUIDE.md`/this
guide's Section 2). Roll back at any time with
`sudo cp /opt/chefpay/chefpay-server.jar.bak-nonweb /opt/chefpay/chefpay-server.jar && sudo systemctl restart chefpay`.

---

## Section 6 — Post-deploy checklist

- [ ] `CHEFPAY_SECURITY_JWT_SECRET` has been changed from the default
      (`dev-only-insecure-secret-change-me-before-any-real-deployment`) to a real random secret.
- [ ] The active Spring profile (`dev` / `postgres` / `mysql`) matches intent — `dev`/SQLite only
      for a demo or a genuinely small single-writer pilot, `postgres`/`mysql` for anything else.
- [ ] HTTPS (Nginx + Let's Encrypt, or equivalent) is in place **before** any real customer or
      payment data flows through the deployment — never run real production traffic over plain
      `http://<ip>:8080`.
- [ ] A backup strategy exists for the production database itself. Confirmed from the project's
      own README: **database backups are not handled by the app at all** — put a real plan in
      place outside ChefPay (managed DB provider snapshots, or a scheduled `pg_dump`/`mysqldump`
      job) before any real customer data goes through it. The app does have a JSON export/import
      "backup" feature in Settings → Offline & Sync, but confirmed (per the same README) that this
      is a **terminal-local** data safety net for the offline cache (IndexedDB), independent of
      network sync — it is not a database backup and does not substitute for one.
- [ ] Change the default admin credentials. Confirmed from the README: `DataSeeder` seeds a
      default admin (`admin` / `admin123`, PIN `1234`) and only logs a warning — nothing forces a
      change. Change this by hand before go-live.
- [ ] Tighten CORS (`allowedOriginPatterns("*")`, confirmed in `SecurityConfig.java`) to the
      actual production origin(s) once they're known — wide-open CORS is acceptable on a trusted
      LAN during development but is a real risk once the deployment is internet-facing.
