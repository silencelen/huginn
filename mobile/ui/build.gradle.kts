import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The shared LOOK: one theme, one markdown renderer, one set of transcript rows,
// one terminal painter, rendered by the phone and the desktop from the SAME
// composables rather than two hand-kept lookalikes.
//
// Why a second module instead of putting these in :core — :core is deliberately
// platform-free logic that a test can run headless. These are composables that
// need foundation, material3 and the icon set, and dragging that into :core would
// make every :core test drag a UI toolkit with it.
//
// Same two targets as :core: androidTarget() for the phone, jvm() for the desktop
// client. No global desktop configuration here — that is what put skiko-windows
// natives inside a Linux .deb during the spike; the desktop natives are named
// once, in :app-desktop, per target.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
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
            // api: this module's public surface NAMES :core types — a row takes a
            // TranscriptGroups.Row, the terminal painter takes a TermGrid — so a
            // consumer cannot use :ui without seeing :core.
            api(project(":core"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            // Tool cards, thinking blocks and subagent groups are all openable, and
            // an expander that forgets on rotation is an expander you re-open every
            // time you turn the phone.
            implementation(compose.runtimeSaveable)
            // Psychology / AccountTree / Build / ExpandMore / ContentCopy. :app
            // already shipped this set, so on Android it resolves to the same
            // androidx artifact the app was already carrying.
            implementation(compose.materialIconsExtended)
        }
        // The GLYPH BLIT is the one part of the terminal painter that cannot be
        // common: the grid walk, run coalescing, cursor and echo are shared, but
        // putting a string on a canvas at an exact baseline needs each platform's
        // own text engine (android.graphics.Paint / skia Font). See CellPainter.kt
        // — an interface handed to the shared painter, NOT an expect/actual, so a
        // caller can substitute one in a test or a preview.
        androidMain.dependencies {}
        jvmMain.dependencies {}
        // JVM ONLY, and only for tests. The grid walk is asserted against a
        // recording CellPainter, which needs a real DrawScope, which needs a real
        // ImageBitmap — and on the Android target that is a stubbed
        // `android.graphics.Bitmap` in a unit test, so the same suite cannot run
        // there. The shared code under test is byte-identical on both targets.
        //
        // `compose.desktop.currentOs` is the skiko NATIVE for this host, and it is
        // scoped to jvmTest deliberately: a global desktop configuration is what
        // put skiko-windows inside a Linux .deb during the spike, and this module
        // publishes a plain jvm variant that must not carry a native at all.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "com.silencelen.huginn.uikit"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
