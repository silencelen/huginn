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
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.silencelen.huginn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.silencelen.huginn"
        minSdk        = 29
        targetSdk     = 35

        // Monotonic versionCode = seconds since 2026-01-01 (epoch 1767225600) —
        // devstore fleet convention. HUGINN_VERSIONCODE overrides for pinned builds.
        versionCode   = System.getenv("HUGINN_VERSIONCODE")?.toIntOrNull()
            ?: (System.currentTimeMillis() / 1000L - 1_767_225_600L).toInt()
        versionName   = "2.14.0"

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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")
}

dependencies {
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
    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    // Background poll that notices when a session starts waiting on you.
    implementation(libs.androidx.work.runtime.ktx)

    // FCM. The one transport that reaches a phone asleep with the app closed, in
    // seconds rather than at the next alarm — everything else here either waits for
    // a beat or arrives on a different app. Needs app/google-services.json, which is
    // NOT a secret: the same values ship inside every copy of the APK.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    debugImplementation(libs.compose.ui.tooling)

    // JVM unit tests. This host has no device and no KVM, so there is no
    // instrumentation/emulator path: these tests are the only automated check on
    // the two hand-rolled parsers (the ANSI renderer and the SSE reader), and
    // they are fed bytes captured from the live daemon.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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
