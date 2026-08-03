import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Resolves the signing keystore and its credentials.
 *
 * Android refuses to install an APK over one signed with a different key, so a
 * stable signature is what makes updates installable without uninstalling first.
 * Debug builds are otherwise signed with a keystore Gradle generates on demand,
 * and CI runs on a fresh machine every time — so every build would get a new key
 * and every release would conflict with the last.
 *
 * Two sources, checked in order:
 *  1. `SM_KEYSTORE_BASE64` and friends, for a key held in repository secrets.
 *  2. The checked-in keystore, so a plain clone produces installable builds.
 */
val keystoreFromEnv: File? = System.getenv("SM_KEYSTORE_BASE64")
    ?.takeIf { it.isNotBlank() }
    ?.let { encoded ->
        // Materialise the secret into the build directory, never the source tree.
        val out = File(layout.buildDirectory.get().asFile, "signing/from-secret.jks")
        out.parentFile.mkdirs()
        // Imported at the top of the file: inside a Kotlin build script the bare
        // `java` prefix resolves to Gradle's java extension, not the JDK package.
        out.writeBytes(Base64.getDecoder().decode(encoded.trim()))
        out
    }

val checkedInKeystore: File = rootProject.file("keystore/sensor-monitor-release.jks")
val signingKeystore: File? = keystoreFromEnv ?: checkedInKeystore.takeIf { it.exists() }

val signingStorePassword: String = System.getenv("SM_KEYSTORE_PASSWORD") ?: "cfscanner"
val signingKeyAlias: String = System.getenv("SM_KEY_ALIAS") ?: "cf-scanner"
val signingKeyPassword: String = System.getenv("SM_KEY_PASSWORD") ?: "cfscanner"

android {
    namespace = "com.playground.sensormonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.playground.sensormonitor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("stable") {
            signingKeystore?.let { ks ->
                storeFile = ks
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        // Debug is what CI publishes, so it must use the stable key rather than
        // the per-machine keystore Gradle would otherwise create.
        getByName("debug") {
            if (signingKeystore != null) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (signingKeystore != null) signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    testImplementation("junit:junit:4.13.2")
}

/** Fails loudly rather than silently shipping an unstably-signed APK. */
tasks.register("verifySigningConfigured") {
    doLast {
        if (signingKeystore == null) {
            throw GradleException(
                "No signing keystore found. Expected ${checkedInKeystore.path} " +
                    "or the SM_KEYSTORE_BASE64 environment variable. Without one, " +
                    "each build gets a different key and updates cannot be installed " +
                    "over previous versions.",
            )
        }
        val source = if (keystoreFromEnv != null) "environment secret" else "checked-in keystore"
        logger.lifecycle("Signing source: $source")
        logger.lifecycle("Signing with: ${signingKeystore.path}")
    }
}
