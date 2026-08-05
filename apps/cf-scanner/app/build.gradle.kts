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
 * Debug builds are signed with a keystore Gradle generates on demand — and CI
 * runs on a fresh machine every time, so every build used to get a brand-new
 * key and every release conflicted with the last.
 *
 * Two sources are supported, checked in this order:
 *
 *  1. **Environment variables** (`CFS_KEYSTORE_BASE64` and friends), used by CI
 *     when the keystore is held in repository secrets. Preferred, because the key
 *     never appears in the repository.
 *  2. **The checked-in keystore** at `keystore/cf-scanner-release.jks`, so a
 *     plain `git clone` produces installable, consistently signed builds with no
 *     setup.
 *
 * The checked-in key is deliberately not a secret: it exists to keep the
 * signature stable for a sample app, not to prove authorship. Anything published
 * to a store should use option 1.
 */
val keystoreFromEnv: File? = System.getenv("CFS_KEYSTORE_BASE64")
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

val checkedInKeystore: File = rootProject.file("keystore/cf-scanner-release.jks")

val signingKeystore: File? = keystoreFromEnv ?: checkedInKeystore.takeIf { it.exists() }

/**
 * Reads a credential from the environment, treating blank as absent.
 *
 * A workflow that references an undefined secret sets the variable to an empty
 * string rather than leaving it unset, so a plain `?:` fallback does not fire
 * and an empty password reaches the signer. That surfaces as the thoroughly
 * misleading "keystore password was incorrect".
 */
fun signingCredential(name: String, fallback: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

val signingStorePassword: String = signingCredential("CFS_KEYSTORE_PASSWORD", "cfscanner")
val signingKeyAlias: String = signingCredential("CFS_KEY_ALIAS", "cf-scanner")
val signingKeyPassword: String = signingCredential("CFS_KEY_PASSWORD", "cfscanner")

android {
    namespace = "com.playground.cfscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.playground.cfscanner"
        minSdk = 24
        targetSdk = 34
        versionCode = 32
        versionName = "0.11.3"
    }

    signingConfigs {
        create("stable") {
            signingKeystore?.let { ks ->
                storeFile = ks
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                // Enable v1 alongside v2/v3 so older devices verify it too.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        // Debug is what CI publishes, so it must use the stable key rather than
        // the per-machine keystore Gradle would otherwise create.
        getByName("debug") {
            if (signingKeystore != null) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            isMinifyEnabled = false
            if (signingKeystore != null) {
                signingConfig = signingConfigs.getByName("stable")
            }
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
    // 1.12.0 provides Material 3 components incl. MaterialSwitch and
    // MaterialAlertDialogBuilder used by the UI.
    implementation("com.google.android.material:material:1.12.0")
    // 1.3.x provides ConcatAdapter, which lets the header, placeholder, and
    // results share a single scrolling list so rows are genuinely recycled.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // ViewModel keeps the scan alive across configuration changes; without it a
    // rotation or the language switch destroyed a scan that can take minutes.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

/** Fails loudly rather than silently shipping an unstably-signed APK. */
tasks.register("verifySigningConfigured") {
    doLast {
        if (signingKeystore == null) {
            throw GradleException(
                "No signing keystore found. Expected ${checkedInKeystore.path} " +
                    "or the CFS_KEYSTORE_BASE64 environment variable. Without one, " +
                    "each build gets a different key and updates cannot be installed " +
                    "over previous versions.",
            )
        }
        val source = if (keystoreFromEnv != null) "environment secret" else "checked-in keystore"
        logger.lifecycle("Signing source: $source")
        logger.lifecycle("Signing with: ${signingKeystore.path}")
    }
}
