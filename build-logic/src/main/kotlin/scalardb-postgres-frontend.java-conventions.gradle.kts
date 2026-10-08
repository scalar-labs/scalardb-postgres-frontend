import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.nullaway
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `java-library`
    id("scalardb-postgres-frontend.base-conventions")
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
    id("net.ltgt.errorprone")
    id("net.ltgt.nullaway")
}

val libs = the<LibrariesForLibs>()

base.archivesName = "scalardb-postgres-frontend-${project.name}"

// The JDK 25 toolchain runs the compiler, Error Prone and NullAway (which need a recent JDK), as in
// ScalarDB Saga. The main code is compiled for Java 17 with --release, which also checks it against
// the Java 17 platform API: it runs inside ScalarDB Cluster nodes, whose baseline is Java 17.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.named<JavaCompile>("compileJava") {
    options.release = 17
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
// Reproducible archives
// ---------------------------------------------------------------------------

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).isNoTimestamp = true
    (options as StandardJavadocDocletOptions).addStringOption("Xmaxwarns", "10000")
}

// ---------------------------------------------------------------------------
// Spotless
// ---------------------------------------------------------------------------

spotless {
    java {
        target("src/*/java/**/*.java")
        importOrder()
        removeUnusedImports()
        forbidWildcardImports()
        googleJavaFormat(libs.versions.google.java.format.get())
        formatAnnotations()
    }
}

// ---------------------------------------------------------------------------
// SpotBugs + FindSecBugs
// ---------------------------------------------------------------------------

spotbugs {
    effort = Effort.MAX
    reportLevel = Confidence.MEDIUM
    showStackTraces = true
    maxHeapSize = "1g"
    excludeFilter = rootProject.file("config/spotbugs/exclude.xml")
}

dependencies {
    spotbugsPlugins(libs.findsecbugs.plugin)
}

tasks.withType<SpotBugsTask>().configureEach {
    val task = name
    reports.create("html") {
        required = true
        outputLocation = layout.buildDirectory.file("reports/spotbugs/$task.html")
    }
    // machine-readable too, for counting and triage
    reports.create("xml") {
        required = true
        outputLocation = layout.buildDirectory.file("reports/spotbugs/$task.xml")
    }
}

// ---------------------------------------------------------------------------
// Error Prone + NullAway
// ---------------------------------------------------------------------------

nullaway {
    annotatedPackages.add("com.scalar.db.frontend.postgres")
}

dependencies {
    errorprone(libs.errorprone.core)
    errorprone(libs.nullaway)
}

tasks.withType<JavaCompile>().configureEach {
    // javac stops printing after 100 warnings; show them all
    options.compilerArgs.addAll(listOf("-Xmaxwarns", "10000", "-Xmaxerrs", "10000"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        enable(
            "MissingOverride",
            "UnusedVariable",
            "FieldCanBeFinal",
        )
        // An enum switch that silently gains a constant is a correctness bug, not a style point
        error("MissingCasesInEnumSwitch")
    }
}

tasks.compileJava {
    options.errorprone.nullaway {
        error()
    }
}

// ---------------------------------------------------------------------------
// Testing
// ---------------------------------------------------------------------------

tasks.named("check") {
    // Javadoc is not part of check by default, so a broken {@link} would surface only when the
    // javadoc jar is built
    dependsOn(tasks.named("javadoc"))
}

dependencies {
    compileOnly(libs.jspecify)
    compileOnly(libs.jcip.annotations)
    compileOnly(libs.spotbugs.annotations)
    testCompileOnly(libs.spotbugs.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
