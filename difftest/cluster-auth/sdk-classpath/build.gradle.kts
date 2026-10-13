// Copies the Cluster client SDK and everything it needs at run time into lib/, for the -cp of a
// frontend in cluster mode. From the repository root: ./gradlew -p difftest/cluster-auth/sdk-classpath lib
plugins { java }

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent { snapshotsOnly() }
        content { includeGroup("com.scalar-labs") }
    }
}

dependencies { runtimeOnly("com.scalar-labs:scalardb-cluster-java-client-sdk:3.20.0-SNAPSHOT") }

tasks.register<Copy>("lib") {
    from(configurations.runtimeClasspath)
    into(layout.projectDirectory.dir("lib"))
}
