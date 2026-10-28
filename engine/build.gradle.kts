plugins {
    id("scalardb-postgres-frontend.java-conventions")
    // scalardb-postgres-frontend-engine on Maven Central, which a ScalarDB Cluster node embeds
    id("scalardb-postgres-frontend.publishing-conventions")
}

description =
    "The ScalarDB PostgreSQL frontend engine: the PostgreSQL wire protocol, a SQL parser and planner " +
    "over ScalarDB operations, and the in-memory operators that complete a query. A ScalarDB " +
    "Cluster node embeds it; the server module runs it standalone."

dependencies {
    // ScalarDB types (the transaction manager and admin) are part of this module's API
    api(libs.scalardb)
    implementation(libs.jsqlparser)
    // json and jsonb values in memory (ScalarDB already brings it)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    // the PostgreSQL JDBC driver is the client in the wire-protocol tests
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.slf4j.simple)
}

mavenPublishing {
    pom {
        name = "ScalarDB PostgreSQL Frontend Engine"
    }
}
