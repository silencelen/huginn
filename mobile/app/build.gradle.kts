// INTERIM (AGP 9 migration): build-script deprecations are compile errors under
// Gradle 9 + Kotlin 2.3. The DSL these hit (kotlinOptions, sourceSets.srcDirs,
// Project.android accessor) still WORKS with android.newDsl=false/builtInKotlin=false
// set in gradle.properties; the forward fix is the new ApplicationExtension DSL +
// migrating :core/:ui to com.android.kotlin.multiplatform.library, tracked as a
// follow-up. Suppressing here keeps the app building on the new toolchain meanwhile.
@file:Suppress("DEPRECATION")

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Properties

// Real release signing lives OUTSIDE the repo (default ~/.huginn-app/keystore.properties,
// overridable via HUGINN_KEYSTORE_PROPERTIES). When the file is absent — CI, fresh
// clones — the release build is produced UNSIGNED so the build still exercises the
// release pipeline; it just can't be shipped. Never check the keystore or its
// properties into git.
val keystorePropsFile = file(
    System.getenv("HUGINN_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.huginn-app/keystore.properties"
)
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Reads app/google-services.json and generates the Firebase config resources.
    // That file is untracked and per-deployment: copy google-services.json.example
    // and fill it from your own Firebase project (see mobile/README.md).
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.silencelen.huginn"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.silencelen.huginn"
        minSdk        = 29
        targetSdk     = 35

        // Monotonic versionCode = seconds since 2026-01-01 (epoch 1767225600) —
        // devstore fleet convention. HUGINN_VERSIONCODE overrides for pinned builds.
        versionCode   = System.getenv("HUGINN_VERSIONCODE")?.toIntOrNull()
            ?: (System.currentTimeMillis() / 1000L - 1_767_225_600L).toInt()
        versionName   = "2.70.0"

        // Single-ABI for smaller APK; phone is arm64-v8a.
        ndk { abiFilters += listOf("arm64-v8a") }

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Explicit, checked-in debug keystore so local debug builds are
        // machine-independent (same lesson as devstore/andvari: AGP's
        // per-machine default keystore breaks update continuity).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "androiddebug"
            keyAlias = "androiddebugkey"
            keyPassword = "androiddebug"
        }
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Keep isDebuggable=false even on the debug buildType so Play
            // Protect doesn't re-prompt on every update (ledger/devstore note).
            isDebuggable     = false
            isMinifyEnabled  = false
            signingConfig    = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            } else {
                logger.warn(
                    "WARNING: ${keystorePropsFile} not found — release build will be " +
                        "UNSIGNED (fine for CI checks, not installable/shippable)."
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")
}

// jvmTarget under the classic kotlin-android plugin (android.builtInKotlin=false):
// kotlinOptions is a hard-deprecated error in Kotlin 2.3, compilerOptions is the DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Wire models, parsers and the pure UI rules — same package names as before
    // the extraction, so nothing in this module needed a new import.
    implementation(project(":core"))
    // The shared LOOK: theme, markdown, transcript rows, terminal painter — the
    // same composables the desktop client renders, under the same package names
    // this module already used, so nothing here needed a new import.
    implementation(project(":ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // No HTTP library named here, on purpose: every socket this app opens is
    // opened by HuginnClient in :core, which brings its own (Ktor, over OkHttp).
    // Naming one again would be a second way to reach the daemon.
    implementation(libs.androidx.datastore.preferences)
    // Background poll that notices when a session starts waiting on you.
    implementation(libs.androidx.work.runtime.ktx)
    // The home-screen widget (FleetWidget): the session fleet on the launcher,
    // drawn from the snapshot every watch observation records.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // FCM. The one transport that reaches a phone asleep with the app closed, in
    // seconds rather than at the next alarm — everything else here either waits for
    // a beat or arrives on a different app. Needs app/google-services.json, which is
    // untracked and per-deployment. Its values are not secret -- every APK carries
    // them in its string resources -- but they name one specific Firebase project,
    // so the tree ships only google-services.json.example.
    // The androidx prompt rather than the framework one: the framework prompt
    // silently failed on the target device — the lock never visibly engaged —
    // and the library exists precisely to absorb those OEM differences.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.exifinterface)
    // Explicit, and load-bearing: biometric 1.1.0 transitively pins
    // androidx.fragment 1.2.5, whose FragmentActivity validates permission
    // request codes as 16-bit. The activity-result launcher generates codes
    // above that range, so EVERY runtime permission request threw
    // "Can only use lower 16 bits for requestCode" and killed the app —
    // measured on-device (SM-F966U, 2.28.0). Fragment 1.8.x drops that check.
    implementation(libs.androidx.fragment)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    debugImplementation(libs.compose.ui.tooling)

    // JVM unit tests. This host has no device and no KVM, so there is no
    // instrumentation/emulator path: these tests are the only automated check the
    // app gets. What is left here is what genuinely needs Android or this
    // module's own classes; the parsers, the SSE reader and the ANSI renderer are
    // tested in :core, against both targets.
    //
    // MockWebServer is gone with SseTest: the client is exercised through Ktor's
    // MockEngine now, which is multiplatform, which is what let that suite move.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// Export a built APK into dist/ under the stamped name
// Huginn-<variant>-<versionCode>-<unixTs>.apk and write the devstore update
// manifest next to it. Release owns dist/latest.json (what the server's
// update-index.sh merges into the served index.json); debug writes
// dist/latest-debug.json so a stray local debug build can never clobber the
// live release manifest.
fun registerExportTask(variant: String, manifestName: String) =
    tasks.register<Copy>("export${variant.replaceFirstChar { it.uppercase() }}Apk") {
        dependsOn("assemble${variant.replaceFirstChar { it.uppercase() }}")
        from(layout.buildDirectory.dir("outputs/apk/$variant"))
        into(rootProject.layout.projectDirectory.dir("dist"))
        include("*.apk")
        val ts = System.currentTimeMillis() / 1000L
        val vc = android.defaultConfig.versionCode ?: 0
        val renamed = "Huginn-$variant-$vc-$ts.apk"
        rename { renamed }
        doLast {
            val manifest = rootProject.layout.projectDirectory.file("dist/$manifestName").asFile
            manifest.parentFile.mkdirs()
            val vn = android.defaultConfig.versionName ?: "0.0.0"
            val apkFile = rootProject.layout.projectDirectory.file("dist/$renamed").asFile
            // Hex-lowercase SHA-256 of the APK bytes — devstore verifies this
            // before handing off to PackageInstaller.
            val md = MessageDigest.getInstance("SHA-256")
            apkFile.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            val sha256 = md.digest().joinToString("") { "%02x".format(it) }
            val builtAt = OffsetDateTime.now().toString()
            val config = variant.replaceFirstChar { it.uppercase() }
            manifest.writeText(
                """{"package":"com.silencelen.huginn","versionCode":$vc,"versionName":"$vn","apk":"$renamed","sha256":"$sha256","sizeBytes":${apkFile.length()},"timestamp":$ts,"builtAt":"$builtAt","config":"$config"}""" + "\n"
            )
        }
    }

val exportDebugApk = registerExportTask("debug", "latest-debug.json")
val exportReleaseApk = registerExportTask("release", "latest.json")

afterEvaluate {
    tasks.findByName("assembleDebug")?.finalizedBy(exportDebugApk)
    tasks.findByName("assembleRelease")?.finalizedBy(exportReleaseApk)
}
