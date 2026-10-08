plugins {
    id("scalardb-postgres-frontend.fat-jar-conventions")
}

description = "The standalone ScalarDB PostgreSQL frontend: the engine as a runnable fat jar."

dependencies {
    implementation(project(":engine"))
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
}

// java -jar build/libs/scalardb-postgres-frontend-<version>.jar <scalardb.properties> [port]
tasks.shadowJar {
    archiveBaseName = "scalardb-postgres-frontend"
    manifest {
        attributes("Main-Class" to "com.scalar.db.frontend.postgres.server.Main")
    }
}
