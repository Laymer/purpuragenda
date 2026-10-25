FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac AS build
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl unzip && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz' -o /tmp/jdk.tar.gz \
 && echo '68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb  /tmp/jdk.tar.gz' | sha256sum -c - \
 && mkdir /opt/jdk && tar -xzf /tmp/jdk.tar.gz -C /opt/jdk --strip-components=1 && rm /tmp/jdk.tar.gz
ENV JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:$PATH
WORKDIR /build
COPY . .
RUN chmod +x mvnw && ./mvnw -B -Dmaven.test.skip=true package

FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl && rm -rf /var/lib/apt/lists/* && useradd --uid 10001 --create-home app
COPY --from=build /opt/jdk /opt/jdk
COPY --from=build /build/backend/bootstrap/target/bootstrap-0.1.0-SNAPSHOT.jar /app/application.jar
COPY ops/docker/backend-entrypoint.sh /app/entrypoint.sh
RUN chmod 755 /app/entrypoint.sh
ENV JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:$PATH SERVER_ADDRESS=0.0.0.0
USER 10001:10001
WORKDIR /app
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s CMD curl -fsS http://127.0.0.1:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
