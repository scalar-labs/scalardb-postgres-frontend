plugins {
    id("scalardb-postgres-frontend.java-conventions")
    alias(libs.plugins.shadow)
}

description = "The standalone ScalarDB PostgreSQL frontend: the engine as a runnable fat jar."

dependencies {
    implementation(project(":engine"))
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)
}

// java -jar build/libs/scalardb-postgres-frontend-<version>.jar <scalardb.properties> [port]
tasks.shadowJar {
    archiveBaseName.set("scalardb-postgres-frontend")
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "com.scalar.db.frontend.postgres.server.Main")
    }
    mergeServiceFiles()
    isZip64 = true
}

// Only the fat jar: a plain jar of Main alone is of no use, and in build/libs it would match the
// scripts' scalardb-postgres-frontend-*.jar glob too
tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
