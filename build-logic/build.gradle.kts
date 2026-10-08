plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.spotless.gradle.plugin)
    implementation(libs.spotbugs.gradle.plugin)
    implementation(libs.errorprone.gradle.plugin)
    implementation(libs.nullaway.gradle.plugin)
    implementation(libs.shadow.gradle.plugin)

    // Workaround: expose the version catalog's type-safe accessors (LibrariesForLibs) to convention
    // plugins, which Gradle does not support yet (https://github.com/gradle/gradle/issues/15383)
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
