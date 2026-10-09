# syntax=docker/dockerfile:1.7
#
# chefpay-server image: Spring Boot backend (REST /api, WebSocket /ws, /platform, /webhooks,
# /manager and /admin static pages).
#
# chefpay-core and chefpay-plugin-api are libraries, not runnable components - they are compiled
# into this image's jar. chefpay-javafx is a desktop client and is intentionally not built here
# (only its pom is copied, because the parent reactor lists it).
#
# The React UI is NOT in this image; it has its own image (chefpay-web/Dockerfile).
#
# BUILD CONTEXT MUST BE THE REPO ROOT (parent pom + sibling modules are needed):
#   docker build -f chefpay-server/Dockerfile -t chefpay/chefpay-server:latest .

# ---------------------------------------------------------------------------
# 1) Build: plugin-api -> core -> server
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
ARG SKIP_TESTS=true

COPY pom.xml ./
COPY chefpay-plugin-api/pom.xml chefpay-plugin-api/pom.xml
COPY chefpay-core/pom.xml       chefpay-core/pom.xml
COPY chefpay-server/pom.xml     chefpay-server/pom.xml
COPY chefpay-javafx/pom.xml     chefpay-javafx/pom.xml

COPY chefpay-plugin-api/src chefpay-plugin-api/src
COPY chefpay-core/src       chefpay-core/src
COPY chefpay-server/src     chefpay-server/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl chefpay-plugin-api,chefpay-core,chefpay-server -am \
        clean package -DskipTests=${SKIP_TESTS}

# ---------------------------------------------------------------------------
# 2) Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble AS runtime
# Tesseract powers the optional supplier-invoice OCR (OcrService). If it is missing the app still
# works - OCR just degrades to manual entry. Build with --build-arg INSTALL_OCR=false to skip it.
ARG INSTALL_OCR=true

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && if [ "$INSTALL_OCR" = "true" ]; then \
        apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng libtesseract5 liblept5 \
     && libdir="/usr/lib/$(uname -m)-linux-gnu" \
     && ln -sf "$libdir/libtesseract.so.5" "$libdir/libtesseract.so" \
     && ln -sf "$libdir/liblept.so.5"      "$libdir/liblept.so"; \
    fi \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 chefpay \
 && useradd  --system --uid 10001 --gid chefpay --home-dir /app --shell /usr/sbin/nologin chefpay

WORKDIR /app
RUN mkdir -p /app/data /app/logs && chown -R chefpay:chefpay /app
COPY --from=build --chown=chefpay:chefpay /build/chefpay-server/target/chefpay-server.jar /app/chefpay-server.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    CHEFPAY_DB_PATH=/app/data/chefpay.db \
    CHEFPAY_LOG_FILE=/app/logs/chefpay-server.log \
    TESSDATA_PATH=/usr/share/tesseract-ocr/5/tessdata

USER chefpay
EXPOSE 8080
VOLUME ["/app/data", "/app/logs"]

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/chefpay-server.jar"]
