FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac AS toolchain
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates python3 && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY ops/toolchain.lock.json ops/toolchain.lock.json
COPY scripts/dev/install-toolchain.py scripts/dev/install-toolchain.py
RUN python3 scripts/dev/install-toolchain.py
ENV JAVA_HOME=/build/.toolchain/jdk PATH=/build/.toolchain/jdk/bin:/build/.toolchain/node/bin:/build/.toolchain/maven/bin:$PATH

FROM toolchain AS backend-build
COPY pom.xml ./
COPY backend/ backend/
COPY acceptance-tests/ acceptance-tests/
COPY quality/ quality/
RUN mvn --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

FROM toolchain AS frontend-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac AS backend
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/* && useradd --uid 10001 --create-home app
COPY --from=toolchain /build/.toolchain/jdk /opt/java
COPY --from=backend-build /build/backend/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar /app/application.jar
RUN mkdir -p /privacy-journal && chown 10001:10001 /privacy-journal
USER 10001:10001
WORKDIR /app
EXPOSE 8081
HEALTHCHECK --interval=5s --timeout=3s --start-period=45s --retries=12 CMD curl -fsS -o /dev/null http://127.0.0.1:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["/opt/java/bin/java", "-XX:MaxRAMPercentage=65", "-jar", "/app/application.jar"]

FROM caddy:2.11.7-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f AS web
RUN setcap -r /usr/bin/caddy && mkdir -p /data /config && chown -R 1000:1000 /data /config
COPY --from=frontend-build /build/frontend/dist/purpuragenda/browser/ /srv/
COPY ops/caddy/Caddyfile /etc/caddy/Caddyfile
USER 1000:1000
HEALTHCHECK --interval=5s --timeout=3s --retries=12 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

FROM axllent/mailpit:v1.32.0@sha256:0790692be935ea4420d085694f7985b1f692ca6a8771ab0e0bd8ba75ac180498 AS mailpit
RUN mkdir /data && chown 10001:10001 /data
USER 10001:10001

FROM postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc AS postgres
COPY --chmod=755 ops/docker/init-database.sh /docker-entrypoint-initdb.d/10-roles.sh
COPY --chmod=755 ops/docker/postgres-health.sh /usr/local/bin/purpuragenda-db-health
USER 999:999
