# syntax=docker/dockerfile:1

# The standalone frontend. The build context is server/build/docker, assembled by the dockerContext
# Gradle task, not this directory: ./gradlew :server:dockerBuild builds the image from it. Gradle
# builds the jars on the host and this file only packages them, as in ScalarDB Saga's server image.
# Run it with a ScalarDB properties file mounted at /scalardb-postgres-frontend/scalardb.properties;
# its values may read the environment as ${env:NAME}. See getting-started/README.md.

# Tag and digest together: the tag says what this is, the digest is what gets pulled, so rebuilding
# a commit yields the same base layer. No Dependabot watches this repository: bump both by hand
# (docker buildx imagetools inspect eclipse-temurin:25-jre-noble).
#
# A JRE, not a JDK: nothing here compiles. 25, the current LTS, although the code targets Java 17:
# 17 is the floor for ScalarDB Cluster nodes that embed the engine, which this image is not.
FROM eclipse-temurin:25-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab

# One layer: OS packages upgraded, since the base image can carry CVEs already fixed upstream, and a
# fixed numeric uid/gid, the one other Scalar server images use, so that a Kubernetes runAsNonRoot
# check passes without a passwd lookup.
# hadolint ignore=DL3005
RUN apt-get update \
 && apt-get upgrade -y --no-install-recommends \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system --gid 201 scalardb \
 && useradd --system --uid 201 --gid 201 --no-create-home scalardb

WORKDIR /scalardb-postgres-frontend

# Owned by root, not by uid 201: the server never writes to disk, and a compromised process must not
# be able to replace a jar for the next start to run.
COPY lib/ ./lib/

USER 201:201

# The JVM reads JAVA_TOOL_OPTIONS itself, as Saga's start script sets the same flags. The heap may use
# most of the container's memory, and running out of it ends the process, for the orchestrator to
# restart, rather than leaving it half working. The Cluster SDK's gRPC loads Netty's native library,
# a restricted method since JDK 24: without native access the JVM warns, and a later release will
# block it. (Caffeine, under ScalarDB core, is on 3 here, not 2, which used sun.misc.Unsafe: see
# server/build.gradle.kts.)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED"
EXPOSE 5432
# java is PID 1, so it receives SIGTERM itself
ENTRYPOINT ["java", "-cp", "/scalardb-postgres-frontend/lib/*", "com.scalar.db.frontend.postgres.server.Main"]
CMD ["/scalardb-postgres-frontend/scalardb.properties", "5432"]

# Last, since they change on every build. dockerBuild passes the project version and the git commit.
# The OCI version label is set too: the base image's says 24.04, Ubuntu's version, not ours.
ARG IMAGE_VERSION=unspecified
ARG IMAGE_REVISION=unspecified
LABEL name="scalardb-postgres-frontend" \
      maintainer="Scalar, Inc." \
      vendor="Scalar, Inc." \
      version="${IMAGE_VERSION}" \
      release="${IMAGE_REVISION}" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.revision="${IMAGE_REVISION}" \
      summary="ScalarDB PostgreSQL Frontend Container Image" \
      description="Serves the PostgreSQL wire protocol on top of ScalarDB, so that PostgreSQL clients and drivers can use ScalarDB transactions."
