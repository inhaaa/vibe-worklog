FROM node:22.23.4-bookworm-slim@sha256:de170b39439eb0e5a3444ad7d60b015614a381e10fecae2e30ec9e735122f38d AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=secret,id=local_build_ca \
    if [ -s /run/secrets/local_build_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/local_build_ca; fi; \
    npm install --global npm@11.21.1 --ignore-scripts --no-fund \
    && npm --version \
    && npm ci --no-fund
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e AS build

ARG TARGETARCH
ADD --checksum=sha256:68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz /tmp/worklog-jdk.tar.gz
ADD --checksum=sha256:6fac98f0c8202efc3135a9e9e42aa61963ec42d1c5cbb40caf231e7b14a24ef6 https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jre_x64_linux_hotspot_25.0.5_8.tar.gz /tmp/worklog-jre.tar.gz
# The CPU archives are available before the matching official Docker tags.
RUN test "$TARGETARCH" = amd64 \
    && mkdir /opt/worklog-jdk /opt/worklog-jre \
    && tar -xzf /tmp/worklog-jdk.tar.gz --strip-components=1 -C /opt/worklog-jdk \
    && tar -xzf /tmp/worklog-jre.tar.gz --strip-components=1 -C /opt/worklog-jre \
    && rm -rf /opt/java/openjdk \
    && mv /opt/worklog-jdk /opt/java/openjdk \
    && rm /tmp/worklog-jdk.tar.gz /tmp/worklog-jre.tar.gz
ENV JAVA_VERSION=jdk-25.0.5+8

WORKDIR /workspace
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip zlib1g=1:1.3.dfsg-3.1ubuntu2.3 libbrotli1=1.1.0-2ubuntu0.1 \
    && rm -rf /var/lib/apt/lists/*
COPY --chmod=0755 mvnw ./mvnw
COPY .mvn/ .mvn/
COPY pom.xml ./pom.xml
COPY LICENSES/ LICENSES/
COPY THIRD_PARTY_NOTICES.md ./
COPY src/ src/
COPY --from=frontend /frontend/dist/ frontend/dist/
COPY scripts/TruststoreRemediation.java /tmp/TruststoreRemediation.java
RUN --mount=type=secret,id=local_build_ca \
    --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    set -eu; \
    if [ -s /run/secrets/local_build_ca ]; then \
        cp /run/secrets/local_build_ca /usr/local/share/ca-certificates/tna-local-build-ca.crt; \
        update-ca-certificates; \
        keytool -importcert -noprompt -alias tna-local-build-ca -cacerts -storepass changeit \
            -file /run/secrets/local_build_ca; \
    fi; \
    javac -d /tmp/tna-security /tmp/TruststoreRemediation.java; \
    java -cp /tmp/tna-security TruststoreRemediation --apply \
        "$(readlink -f "$JAVA_HOME/lib/security/cacerts")" /tmp/tna-cacerts.original; \
    java -cp /tmp/tna-security TruststoreRemediation --check \
        "$(readlink -f "$JAVA_HOME/lib/security/cacerts")"; \
    ./mvnw -B -Dmaven.test.skip=true -Dfrontend.skip=true package

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5 AS runtime-base

COPY --from=build /opt/worklog-jre/ /tmp/worklog-jre/
RUN rm -rf /opt/java/openjdk \
    && mv /tmp/worklog-jre /opt/java/openjdk
ENV JAVA_VERSION=jdk-25.0.5+8

RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade zlib1g=1:1.3.dfsg-3.1ubuntu2.3 libbrotli1=1.1.0-2ubuntu0.1 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /tmp/tna-security/TruststoreRemediation.class /tmp/tna-security/TruststoreRemediation.class
RUN java -cp /tmp/tna-security TruststoreRemediation --apply \
        "$(readlink -f "$JAVA_HOME/lib/security/cacerts")" /tmp/tna-cacerts.original \
    && java -cp /tmp/tna-security TruststoreRemediation --check \
        "$(readlink -f "$JAVA_HOME/lib/security/cacerts")" \
    && rm -f /tmp/tna-cacerts.original /tmp/tna-security/TruststoreRemediation.class

FROM runtime-base AS migrate

RUN groupadd --gid 10001 tna \
    && useradd --uid 10001 --gid tna --system --no-create-home --no-log-init tna
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/*.jar /app/application.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
CMD ["migrate"]

FROM runtime-base AS runtime

RUN groupadd --gid 10001 tna \
    && useradd --uid 10001 --gid tna --system --no-create-home --no-log-init tna
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/*.jar /app/application.jar
USER 10001:10001
EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
