FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e AS build

WORKDIR /workspace
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --chmod=0755 mvnw ./mvnw
COPY .mvn/ .mvn/
COPY pom.xml ./pom.xml
COPY LICENSES/ LICENSES/
COPY THIRD_PARTY_NOTICES.md ./
COPY src/ src/
RUN ./mvnw -B -Dmaven.test.skip=true package

FROM build AS migrate
CMD ["./mvnw", "-B", "flyway:migrate"]

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5 AS runtime

RUN groupadd --gid 10001 tna \
    && useradd --uid 10001 --gid tna --system --no-create-home --no-log-init tna
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/*.jar /app/application.jar
USER 10001:10001
EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
