import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The Compose Multiplatform desktop client.
//
// It owns a window, a settings file and desktop-shaped composables — nothing
// else. Everything it knows about huginn-appd (routes, timeouts, SSE framing,
// wire models, markdown, terminal grid) comes from :core, which is the entire
// point of the migration: the Electron client re-implemented ~2,545 lines of
// that by hand and drifted from it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    // For the settings file. Without it `@Serializable` compiles but the
    // generated `.serializer()` does not exist, and the error names neither the
    // plugin nor the class — it says "unresolved reference: serializer".
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Same 17 as :app and :core. jvmToolchain rather than a compilerOptions
    // jvmTarget so javac and kotlinc cannot disagree — they did on the andvari
    // desktop module, and the error names neither.
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    // PER-TARGET, and this is a trap with teeth. `compose.desktop.windows_x64`
    // declared globally put skiko-windows-x64 inside a LINUX .deb during the
    // spike — it built, packaged and exited 0, and only failed at runtime on the
    // machine it was supposedly built for. `currentOs` resolves the skiko native
    // for the host doing the build, which is right for `run` and `packageDeb`
    // here. Phase 4 adds a Windows path, and it must add it as its OWN
    // configuration (a separate jpackage-under-wine step), never by widening
    // this one.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}

// Headless verification (`xvfb-run ./gradlew :app-desktop:run`) needs BOTH of
// these. The Gradle daemon runs with `java.awt.headless=true` and JavaExec
// inherits it, so the app died in `getGlobalDensity` with a HeadlessException
// even against a perfectly good Xvfb display — the stack trace says "No X11
// DISPLAY variable was set", which is the wrong diagnosis and cost real time.
// DISPLAY is re-exported explicitly so the answer does not depend on which
// daemon Gradle decided to reuse.
tasks.withType<JavaExec>().configureEach {
    systemProperty("java.awt.headless", "false")
    System.getenv("DISPLAY")?.let { environment("DISPLAY", it) }
    System.getenv("XAUTHORITY")?.let { environment("XAUTHORITY", it) }
}

compose.desktop {
    application {
        mainClass = "com.silencelen.huginn.desktop.MainKt"
        nativeDistributions {
            // Linux only for phase 3a. Msi is deliberately absent: on Linux
            // `packageMsi` exits 0 and produces NOTHING (onlyIf false), so
            // declaring it here would make the build look like it ships Windows.
            targetFormats(TargetFormat.Deb)
            packageName = "huginn-desktop-kt"
            packageVersion = "0.1.0"
            description = "Huginn desktop client"
            vendor = "silencelen"
            // The jlink runtime image is minimal by default. java.net.http and
            // java.sql are not needed (Ktor uses OkHttp, no JDBC), but Ktor's
            // OkHttp engine and the coroutines debug agent both want
            // java.instrument/java.management, and jdk.unsupported is what
            // OkHttp's Okio reaches sun.misc.Unsafe through. From
            // `gradlew :app-desktop:suggestRuntimeModules`.
            modules("java.instrument", "java.management", "jdk.unsupported")
        }
    }
}
