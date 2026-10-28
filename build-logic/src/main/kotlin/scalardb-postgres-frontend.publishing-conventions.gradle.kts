plugins {
    id("scalardb-postgres-frontend.base-conventions")
    id("com.vanniktech.maven.publish")
}

// Publishing to Maven Central, as ScalarDB Saga does: the Vanniktech plugin adds the sources and
// javadoc jars Central requires for a java-library and uploads through the Central Portal API. Only
// the engine applies this; the standalone server ships as a fat jar and an image, not on Central.

// The license travels inside every published jar, not only in the repository
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    // the base Jar type, so the javadoc jar the plugin contributes is included too
    metaInf {
        from(rootProject.file("LICENSE"))
    }
}

mavenPublishing {
    // the same name as the jar (base.archivesName in the java conventions), not the project name
    coordinates(
        groupId = project.group.toString(),
        artifactId = "scalardb-postgres-frontend-${project.name}",
        version = project.version.toString(),
    )

    // A release is uploaded as a USER_MANAGED deployment, which someone then releases on the Central
    // Portal: a released version can never be replaced. Snapshots go straight to the snapshot
    // repository
    publishToMavenCentral()

    // Central rejects unsigned releases, but publishToMavenLocal (as a Cluster build uses) needs no
    // key: sign only when CI provides one (ORG_GRADLE_PROJECT_signingInMemoryKey). An unset secret
    // arrives as an empty string, which counts as none; release.yml refuses to publish without one
    if (!providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()) {
        signAllPublications()
    }

    pom {
        // the artifactId unless the module names itself, as the engine does
        name = "scalardb-postgres-frontend-${project.name}"
        description = provider {
            checkNotNull(project.description) {
                "Project '${project.path}' is published, so it must set a description: Maven " +
                    "Central rejects a POM without one"
            }
        }
        url = "https://github.com/scalar-labs/scalardb-postgres-frontend"
        inceptionYear = "2026"

        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "scalar-labs"
                name = "Scalar, Inc."
                url = "https://scalar-labs.com/"
            }
        }

        scm {
            url = "https://github.com/scalar-labs/scalardb-postgres-frontend"
            connection = "scm:git:https://github.com/scalar-labs/scalardb-postgres-frontend.git"
            developerConnection = "scm:git:ssh://git@github.com/scalar-labs/scalardb-postgres-frontend.git"
        }

        issueManagement {
            system = "GitHub Issues"
            url = "https://github.com/scalar-labs/scalardb-postgres-frontend/issues"
        }
    }
}
