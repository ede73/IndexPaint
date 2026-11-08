import java.io.FileInputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.jar.JarFile
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import groovy.json.JsonSlurper
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.play.publisher)
}

val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }.toSet()
val releaseTaskNames = setOf(
    "generateSignedBundle", "verifyReleaseSigning", "verifyReleaseBundleSignature",
    "bundleRelease", "publishInternal", "publishReleaseBundle"
)
val publishingTaskNames = setOf("publishInternal", "publishReleaseBundle")
val releaseRequested = requestedTaskNames.any(releaseTaskNames::contains)
val publishingRequested = requestedTaskNames.any(publishingTaskNames::contains)
val defaultKeystore = rootProject.file("signing/IndexPaint-release.keystore")
    .takeIf { it.isFile }
    ?: rootProject.file("../IndexPaint/signing/IndexPaint-release.keystore")
val configuredKeystorePath = System.getenv("INDEXPAINT_KEYSTORE_PATH") ?: defaultKeystore.absolutePath

fun releaseSecret(environmentName: String): String? {
    if (!releaseRequested) return null
    return System.getenv(environmentName)?.takeIf(String::isNotBlank)
        ?: error("GitHub Actions secret $environmentName is missing.")
}

val releaseStorePassword = releaseSecret("INDEXPAINT_KEYSTORE_PASSWORD")
val releaseKeyPassword = releaseSecret("INDEXPAINT_KEY_PASSWORD")
val publisherCredentialFile = layout.buildDirectory.file("tmp/play-publisher/credentials.json")

android {
    namespace = "fi.iki.ede.IndexPaint"
    compileSdk = 36

    defaultConfig {
        applicationId = "fi.iki.ede.IndexPaint"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("playUpload") {
            storeFile = rootProject.file(configuredKeystorePath)
            storePassword = releaseStorePassword
            keyAlias = System.getenv("INDEXPAINT_KEY_ALIAS") ?: "vh"
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("playUpload")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

play {
    // Keep programmatic publishing pinned to the private internal test track.
    track.set("internal")
    defaultToAppBundles.set(true)
    // Ask Play for existing codes and automatically resolve a fresh code on publish.
    resolutionStrategy.set(ResolutionStrategy.AUTO)
    serviceAccountCredentials.set(publisherCredentialFile.get().asFile)
}

val prepareLocalPlayPublisherCredential = tasks.register("prepareLocalPlayPublisherCredential") {
    group = "publishing"
    description = "Validates the Play publishing credential and writes a short-lived private build file."
    onlyIf { publishingRequested }
    doLast {
        val json = System.getenv("ANDROID_PUBLISHER_CREDENTIALS")?.takeIf(String::isNotBlank)
            ?: error("GitHub Actions secret ANDROID_PUBLISHER_CREDENTIALS is missing.")
        val parsed = JsonSlurper().parseText(json) as? Map<*, *>
            ?: error("The Play publishing credential is not a JSON object.")
        check(parsed["type"] == "service_account" && parsed["private_key"] is String) {
            "The Play publishing credential is not a service-account JSON key."
        }
        val target = publisherCredentialFile.get().asFile
        target.parentFile.mkdirs()
        target.delete()
        Files.writeString(target.toPath(), json)
        try {
            Files.setPosixFilePermissions(target.toPath(), setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
            ))
        } catch (_: UnsupportedOperationException) {
            // The release publisher is intended for the local macOS workstation.
        }
    }
}

val removeLocalPlayPublisherCredential = tasks.register("removeLocalPlayPublisherCredential") {
    group = "publishing"
    description = "Removes the temporary Play publishing credential from the build directory."
    onlyIf { publishingRequested }
    doLast { publisherCredentialFile.get().asFile.delete() }
}

tasks.configureEach {
    if (name == "publishReleaseBundle") {
        dependsOn(prepareLocalPlayPublisherCredential)
        finalizedBy(removeLocalPlayPublisherCredential)
    }
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Checks the local/CI signing inputs and prints the configured certificate fingerprint."
    doLast {
        val storeFile = rootProject.file(configuredKeystorePath)
        check(storeFile.isFile) { "Keystore file was not found at: ${storeFile.absolutePath}" }
        val storePassword = checkNotNull(releaseStorePassword)
        val alias = System.getenv("INDEXPAINT_KEY_ALIAS") ?: "vh"
        val keyPassword = checkNotNull(releaseKeyPassword)
        val keyStore = KeyStore.getInstance(System.getenv("INDEXPAINT_KEYSTORE_TYPE") ?: "JKS")
        FileInputStream(storeFile).use { keyStore.load(it, storePassword.toCharArray()) }
        check(keyStore.containsAlias(alias)) { "Alias '$alias' was not found in the configured keystore." }
        check(keyStore.getKey(alias, keyPassword.toCharArray()) is java.security.PrivateKey) {
            "Alias '$alias' does not contain a private signing key or its password is incorrect."
        }
        val certificate = checkNotNull(keyStore.getCertificate(alias))
        val fingerprint = MessageDigest.getInstance("SHA-1").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }
        logger.lifecycle("Configured signing certificate SHA-1: $fingerprint")
    }
}

tasks.configureEach {
    if (name == "bundleRelease" || name == "publishReleaseBundle") dependsOn(verifyReleaseSigning)
}

tasks.register("verifyReleaseBundleSignature") {
    group = "verification"
    description = "Verifies that the generated AAB is signed by the configured keystore alias."
    dependsOn("bundleRelease")
    doLast {
        val bundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab").get().asFile
        check(bundle.isFile) { "Release AAB was not generated: ${bundle.absolutePath}" }

        val storeFile = rootProject.file(configuredKeystorePath)
        val keyStore = KeyStore.getInstance(System.getenv("INDEXPAINT_KEYSTORE_TYPE") ?: "JKS")
        FileInputStream(storeFile).use {
            keyStore.load(it, checkNotNull(releaseStorePassword).toCharArray())
        }
        val alias = System.getenv("INDEXPAINT_KEY_ALIAS") ?: "vh"
        val expectedCertificate = checkNotNull(keyStore.getCertificate(alias))

        val signers = linkedSetOf<String>()
        JarFile(bundle, true).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                jar.getInputStream(entry).use { input -> input.copyTo(OutputStream.nullOutputStream()) }
                entry.certificates?.forEach { certificate ->
                    signers += MessageDigest.getInstance("SHA-1").digest(certificate.encoded)
                        .joinToString(":") { "%02X".format(it) }
                }
            }
        }
        val expectedFingerprint = MessageDigest.getInstance("SHA-1").digest(expectedCertificate.encoded)
            .joinToString(":") { "%02X".format(it) }
        check(signers.isNotEmpty()) { "The generated AAB does not contain a verifiable signature." }
        check(signers.all { it == expectedFingerprint }) {
            "AAB signer does not match configured alias '$alias'. Expected $expectedFingerprint; found ${signers.joinToString()}"
        }
        logger.lifecycle("Verified signed AAB: ${bundle.absolutePath}")
        logger.lifecycle("AAB signer SHA-1: $expectedFingerprint")
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.core:core:1.9.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
