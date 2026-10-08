// A runnable fat jar. Applied through build-logic like every other plugin: a plugin a module applies
// itself puts that module in its own classloader, and Spotless's shared build service then clashes
// across modules.
plugins {
    id("scalardb-postgres-frontend.java-conventions")
    id("com.gradleup.shadow")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier = ""
    mergeServiceFiles()
    isZip64 = true
}

// Only the fat jar: a plain jar of the module alone is of no use, and in build/libs it would also
// match the scripts' scalardb-postgres-frontend-*.jar glob
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}
