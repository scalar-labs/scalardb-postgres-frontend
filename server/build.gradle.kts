plugins {
    id("scalardb-postgres-frontend.fat-jar-conventions")
}

description = "The standalone ScalarDB PostgreSQL frontend: the engine as a runnable fat jar."

dependencies {
    implementation(project(":engine"))
    // Standalone authentication logs clients in through the Cluster client SDK, which cluster mode
    // needs on the classpath anyway (java -cp frontend.jar:scalardb-cluster-java-client-sdk.jar ...)
    compileOnly(libs.scalardb.cluster.client) { isTransitive = false } // its own classes only
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

// The container image's build context, from which dockerBuild builds it (see server/src/docker).
// Plain jars rather than the fat jar, with the Cluster client SDK added, so that one image serves
// every mode, auth included, and Gradle resolves each library once across the frontend and the SDK.
val image: Configuration by configurations.creating {
    extendsFrom(configurations.runtimeClasspath.get())
    // The SDK asks for slf4j 2, which ignores the slf4j-simple 1.7 binding and would log nothing;
    // the fat jar has always run the SDK on the 1.7 API (difftest/cluster-auth)
    resolutionStrategy.force(libs.slf4j.api)
}

dependencies {
    image(libs.scalardb.cluster.client)
}

val imageJar by tasks.registering(Jar::class) {
    from(sourceSets.main.get().output)
    archiveFileName = "scalardb-postgres-frontend-server.jar"
    destinationDirectory = layout.buildDirectory.dir("image-jar")
}

tasks.register<Sync>("dockerContext") {
    description = "Assembles the container image's build context in build/docker."
    from("src/docker")
    into(layout.buildDirectory.dir("docker"))
    into("lib") {
        from(imageJar)
        from(image)
    }
}

// The image, into the local Docker daemon, labelled with the version and the commit
tasks.register<Exec>("dockerBuild") {
    description = "Builds the container image scalardb-postgres-frontend:<version>."
    dependsOn("dockerContext")
    val revision = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText
    workingDir = layout.buildDirectory.dir("docker").get().asFile
    commandLine(
        "docker", "build",
        "--build-arg", "IMAGE_VERSION=${project.version}",
        "--build-arg", "IMAGE_REVISION=${revision.get().trim()}",
        "-t", "scalardb-postgres-frontend:${project.version}",
        ".",
    )
}
