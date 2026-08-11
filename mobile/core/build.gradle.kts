import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The shared brain: parsers, wire models, route resolution and the pure UI rules
// that have no business knowing they are on Android. Two targets today —
// androidTarget() for the app that consumes it, jvm() so the same code can be
// tested (and later run) off-device. No native/JS target: nothing needs one yet,
// and every extra target is compile time on every build.
//
// Compose Multiplatform is here for one reason: shared code names
// androidx.compose.ui types — Color in the ANSI palette, AnnotatedString in the
// markdown and syntax renderers. CMP publishes those under the SAME package
// names as the AndroidX artifacts, so the moved files needed no import changes
// and :app keeps resolving them from its own Compose BOM.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: :app writes `Json.decodeFromString` and
            // names Color/AnnotatedString from types this module exposes, so the
            // dependency is part of :core's public surface, not an internal detail.
            api(libs.kotlinx.serialization.json)
            api(libs.coroutines.core)
            api(compose.runtime)
            api(compose.ui)
            // Also api: HuginnClient's constructor names HttpClientEngine, so a
            // test — or the desktop client — can hand it one.
            api(libs.ktor.client.core)
        }
        // The engine is the one part of the HTTP stack that CANNOT be common:
        // ktor-client-okhttp publishes JVM variants only, so commonMain has no
        // symbol for it. Hence the expect/actual in data/Platform.kt — two
        // three-line files, rather than threading an engine parameter through
        // the app's eight HuginnClient call sites.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        commonTest.dependencies {
            // kotlin.test, not JUnit: commonTest must compile for every target.
            // NOTE the argument order differs from JUnit — see the header of
            // TerminalGridTest.kt before touching an assertion here.
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // Why SseTest could finally leave :app: a mock engine that is itself
            // multiplatform, so one source tests the SSE reader on both targets.
            implementation(libs.ktor.client.mock)
        }
    }
}

android {
    namespace = "com.silencelen.huginn.core"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
