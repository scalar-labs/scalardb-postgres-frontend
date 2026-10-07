// Root project has no source code. Shared build logic is in build-logic/ convention plugins.

// This block exists for Dependabot, not for Gradle: Dependabot reads repositories only from the
// top-level build and settings files, and the Error Prone and SpotBugs Gradle plugins are published
// to the Gradle Plugin Portal only. Modules get their repositories from the java conventions.
repositories {
    gradlePluginPortal()
    mavenCentral()
}
