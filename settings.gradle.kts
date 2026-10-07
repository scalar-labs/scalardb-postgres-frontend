pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "scalardb-postgres-frontend"

// engine: the SQL engine and the PostgreSQL protocol server, a library a ScalarDB Cluster node can
// embed. server: the standalone server, a fat jar around the engine.
include("engine")
include("server")
