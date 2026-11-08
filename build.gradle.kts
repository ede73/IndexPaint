// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

tasks.register("generateSignedBundle") {
    group = "build"
    description = "Builds and verifies the signed release Android App Bundle."
    dependsOn(":app:verifyReleaseBundleSignature")
    doLast {
        val bundle = project(":app").layout.buildDirectory
            .file("outputs/bundle/release/app-release.aab").get().asFile
        check(bundle.isFile) { "Signed Android App Bundle was not generated." }
        logger.lifecycle("Signed Android App Bundle ready: ${bundle.absolutePath}")
    }
}

tasks.register("publishInternal") {
    group = "publishing"
    description = "Builds, verifies, and publishes the signed release bundle to Google Play Internal testing."
    dependsOn(":app:publishReleaseBundle")
    finalizedBy(":app:removeLocalPlayPublisherCredential")
}

tasks.configureEach {
    if (name.startsWith("commitEditFor")) {
        val commitTask = this
        project(":app").tasks.named("removeLocalPlayPublisherCredential") {
            mustRunAfter(commitTask)
        }
    }
}
