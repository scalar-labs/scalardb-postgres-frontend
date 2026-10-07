import com.diffplug.spotless.FormatterFunc
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `java-library`
    id("scalardb-postgres-frontend.base-conventions")
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
    id("net.ltgt.errorprone")
}

val libs = the<LibrariesForLibs>()

base.archivesName = "scalardb-postgres-frontend-${project.name}"

// Java 17: the code runs inside ScalarDB Cluster nodes, whose baseline it must match
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    // ScalarDB 4.0.0-SNAPSHOT is not published; it comes from ./gradlew :core:publishToMavenLocal
    mavenLocal()
    mavenCentral()
}

configurations.all {
    resolutionStrategy {
        // the same pin as ScalarDB's own build
        force(libs.jackson.core)
    }
}

// ---------------------------------------------------------------------------
// Spotless
// ---------------------------------------------------------------------------

spotless {
    java {
        target("src/*/java/**/*.java")
        importOrder()
        removeUnusedImports()
        custom(
            "forbidWildcardImports",
            FormatterFunc { source ->
                if (Regex("(?m)^import\\s+(?:static\\s+)?[^;\\n]*\\*;\\R?").containsMatchIn(source)) {
                    throw AssertionError("Wildcard imports are not allowed")
                }
                source
            },
        )
        googleJavaFormat(libs.versions.google.java.format.get())
    }
}

// ---------------------------------------------------------------------------
// SpotBugs and Error Prone
// ---------------------------------------------------------------------------

spotbugs {
    excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
}

tasks.withType<SpotBugsTask>().configureEach {
    reports.create("html") {
        required.set(true)
    }
}

dependencies {
    compileOnly(libs.spotbugs.annotations)
    testCompileOnly(libs.spotbugs.annotations)
    errorprone(libs.errorprone.core)
}

// ---------------------------------------------------------------------------
// Testing
// ---------------------------------------------------------------------------

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.inline)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging.showStandardStreams = true
}
