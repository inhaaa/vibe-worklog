FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e AS build

WORKDIR /workspace
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip zlib1g=1:1.3.dfsg-3.1ubuntu2.3 \
    && rm -rf /var/lib/apt/lists/*
COPY --chmod=0755 mvnw ./mvnw
COPY .mvn/ .mvn/
COPY pom.xml ./pom.xml
COPY LICENSES/ LICENSES/
COPY THIRD_PARTY_NOTICES.md ./
COPY src/ src/
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
    ./mvnw -B -Dmaven.test.skip=true package

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5 AS runtime-base

RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade zlib1g=1:1.3.dfsg-3.1ubuntu2.3 \
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
