# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 — build
# ---------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Dependencies resolve in their own layer so a source-only change does not
# re-download the world on every push.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# ---------------------------------------------------------------------------
# Stage 2 — expand the fat jar and pre-train a CDS archive
#
# Cloud Run runs with min-instances=0, so every idle period ends in a cold JVM
# start that the user waits on and that is billed as CPU time. Class Data
# Sharing memory-maps pre-parsed class metadata instead of reading and verifying
# it from the jar on each boot; measured here at roughly 28% off startup.
#
# The training run boots the real Spring context and exits at refresh, so the
# archive reflects the classes this application actually loads.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS optimizer
WORKDIR /stage

COPY --from=builder /build/target/monolith-api-1.0.0.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination extracted

WORKDIR /stage/extracted
RUN java -XX:ArchiveClassesAtExit=./app.jsa -Dspring.context.exit=onRefresh -jar app.jar

# ---------------------------------------------------------------------------
# Stage 3 — runtime
#
# Must stay glibc-based, not Alpine/musl: google-cloud-bigquery pulls in
# grpc-netty-shaded, which bundles a native BoringSSL/Netty library compiled
# against glibc. On musl the JVM aborts with SIGABRT before it binds the port.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Cloud Run does not require a non-root user, but running as one costs nothing
# and contains the blast radius of a container escape.
RUN groupadd --system --gid 1001 app \
 && useradd --system --uid 1001 --gid app --no-create-home app

# lib/ holds the dependencies and changes only when pom.xml does; the two thin
# layers after it are the ones that actually churn per deploy.
COPY --from=optimizer --chown=app:app /stage/extracted/lib ./lib
COPY --from=optimizer --chown=app:app /stage/extracted/app.jar ./app.jar
COPY --from=optimizer --chown=app:app /stage/extracted/app.jsa ./app.jsa

USER app

ENV PORT=8080
EXPOSE 8080

# MaxRAMPercentage: the JVM default of 25% wastes most of a 512Mi Cloud Run
#   container. 70% leaves headroom for metaspace, thread stacks, and netty's
#   direct buffers, which live outside the heap.
# SerialGC: at --cpu=1 the parallel collectors buy nothing and cost startup
#   time and footprint.
# Xshare:auto: if the archive ever mismatches the JVM, degrade to a normal
#   start with a warning rather than refusing to boot.
ENV JAVA_TOOL_OPTIONS="\
-XX:MaxRAMPercentage=70 \
-XX:+UseSerialGC \
-XX:SharedArchiveFile=/app/app.jsa \
-Xshare:auto \
-Djava.security.egd=file:/dev/./urandom \
-Dfile.encoding=UTF-8"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
