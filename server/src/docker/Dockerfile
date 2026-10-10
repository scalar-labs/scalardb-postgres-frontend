# The standalone frontend. Build the context first, from the repository root:
#   ./gradlew :server:dockerContext && docker build -t scalardb-postgres-frontend server/build/docker
# Run it with a ScalarDB properties file mounted at /scalardb-postgres-frontend/scalardb.properties;
# its values may read the environment as ${env:NAME}. See getting-started/README.md.
FROM eclipse-temurin:17-jre

RUN groupadd --system frontend && useradd --system --gid frontend --no-create-home frontend
COPY lib/ /scalardb-postgres-frontend/lib/
WORKDIR /scalardb-postgres-frontend
USER frontend

# The JVM reads JAVA_TOOL_OPTIONS itself; by default the heap may use most of the container's memory
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 5432
ENTRYPOINT ["java", "-cp", "/scalardb-postgres-frontend/lib/*", "com.scalar.db.frontend.postgres.server.Main"]
CMD ["/scalardb-postgres-frontend/scalardb.properties", "5432"]
