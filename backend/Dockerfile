# syntax=docker/dockerfile:1
#
# Multi-stage build for the Untangle backend.
#   Stage 1 (build): compile with the full JDK and Maven, then split the fat jar into layers.
#   Stage 2 (runtime): a slim JRE image, a non-root user and only the files needed to run.

# ---------- Stage 1: build ----------
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /workspace

# Copy the build definition first so dependency downloads are cached between code changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests, formatting and coverage run in CI and `make test`, not in the image build.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true \
 && cp target/untangle-backend-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21.0.13_9-jre-noble AS runtime

# curl is only used by the container HEALTHCHECK below.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system untangle \
 && useradd --system --gid untangle --no-create-home --shell /usr/sbin/nologin untangle

WORKDIR /app

# Layers ordered from least to most frequently changed, so a code change only rebuilds the last one.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER untangle:untangle
EXPOSE 8080

# Container-aware JVM: size the heap from the container memory limit, and crash (so Docker
# restarts us) instead of limping along after an OutOfMemoryError.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
