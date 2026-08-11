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

// ONE place the version is written, and everything downstream reads it: the .deb
// control file, the Windows installer's name and registry entry, the generated
// BuildInfo the updater compares against, and the release script's gates. The
// Electron client keeps its version in package.json and had the same rule; a
// Gradle module has no equivalent well-known file, so this is it.
//
// A plain text file rather than a gradle.properties key on purpose: the release
// script must read it from bash without invoking Gradle (the version gate runs
// BEFORE the build, and starting a daemon to learn a three-digit string would
// make the cheapest gate the slowest).
val appVersion: String = file("version.txt").readText().trim()
version = appVersion

kotlin {
    // Same 17 as :app and :core. jvmToolchain rather than a compilerOptions
    // jvmTarget so javac and kotlinc cannot disagree — they did on the andvari
    // desktop module, and the error names neither.
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    // Theme, markdown, transcript rows and the terminal painter — rendered from
    // the same source as the phone, which is the whole point of phase 3b. What is
    // left in this module is the desktop FRAME: window, panes, key handling.
    implementation(project(":ui"))

    // PER-TARGET, and this is a trap with teeth. `compose.desktop.windows_x64`
    // declared globally put skiko-windows-x64 inside a LINUX .deb during the
    // spike — it built, packaged and exited 0, and only failed at runtime on the
    // machine it was supposedly built for. `currentOs` resolves the skiko native
    // for the host doing the build, which is right for `run` and `packageDeb`
    // here. The Windows path is its OWN configuration further down
    // (`windowsRuntimeClasspath`), which is the only correct way to add one:
    // never by widening this declaration.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // The updater's pure parts (semver, manifest parse, sha256, feed pinning) are
    // unit-tested. kotlin.test, matching :core and :ui — and NOTE its argument
    // order is (expected, actual, message), the reverse of JUnit's. See the
    // DESKTOP-MIGRATION trap list.
    // Left on Gradle's default JUnit 4 runner rather than switched to the
    // platform: `kotlin("test")` picks its own variant from whatever the test
    // task uses, and calling `useJUnitPlatform()` here would leave the Jupiter
    // ENGINE off the classpath — a suite that is discovered as zero tests and
    // exits 0. The release script asserts the count for the same reason.
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
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

// ---------------------------------------------------------------- version stamp
//
// The running app has to know its own version to compare against the update
// feed, and reading it back out of the jar manifest only works when there IS a
// jar — `./gradlew :app-desktop:run` has none. A generated constant works in
// both, and `version.txt` remains the single writable copy.
val generateBuildInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/buildinfo")
    val v = appVersion
    inputs.property("version", v)
    outputs.dir(outDir)
    doLast {
        val f = outDir.get().file("com/silencelen/huginn/desktop/update/BuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            """
            |package com.silencelen.huginn.desktop.update
            |
            |/** GENERATED from app-desktop/version.txt — do not edit. */
            |object BuildInfo {
            |    const val VERSION: String = "$v"
            |}
            |
            """.trimMargin()
        )
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(generateBuildInfo)

// ------------------------------------------------- the Windows cross-build input
//
// jpackage CANNOT cross-compile: on Linux its only valid types are app-image,
// rpm and deb, and `packageMsi` here exits 0 having produced NOTHING. The
// Windows build therefore runs the WINDOWS jpackage.exe under wine, and what it
// needs from Gradle is a directory of jars that are right for Windows.
//
// That is a DIFFERENT classpath, in exactly one artifact: skiko ships a native
// per platform, and `compose.desktop.currentOs` above resolves the Linux one.
// Widening that declaration is the trap the spike hit — declaring
// `compose.desktop.windows_x64` globally put skiko-windows-x64 inside the LINUX
// .deb, which built, packaged and exited 0 and only failed on the machine it was
// built for. So this is its own resolvable configuration, and the Linux one is
// untouched.
//
// Its attributes are COPIED from the real runtimeClasspath rather than written
// out by hand (usage, category, jvm environment, kotlin platform type). Hand-written
// attributes are a second declaration of the same facts and drift silently: get one
// wrong and resolution either fails with an ambiguity dump or, worse, quietly picks
// a variant that is not the jvm one.
val windowsRuntimeClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    windowsRuntimeClasspath(project(":core"))
    windowsRuntimeClasspath(project(":ui"))
    windowsRuntimeClasspath(compose.desktop.windows_x64)
    windowsRuntimeClasspath(compose.material3)
    windowsRuntimeClasspath(compose.materialIconsExtended)
}

afterEvaluate {
    val runtime = configurations.getByName("runtimeClasspath")
    runtime.attributes.keySet().forEach { key ->
        @Suppress("UNCHECKED_CAST")
        val typed = key as org.gradle.api.attributes.Attribute<Any>
        windowsRuntimeClasspath.attributes.attribute(typed, runtime.attributes.getAttribute(typed)!!)
    }
}

/**
 * Everything jpackage.exe needs on the app classpath, laid out flat, for a
 * Windows x64 host. Consumed by scripts/release-desktop.sh, which then runs the
 * Windows jlink + jpackage under wine against it.
 *
 * A `Sync` rather than a `Copy`: a stale skiko-linux jar left behind from an
 * earlier layout would be silently bundled into the Windows image and only fail
 * at launch on the owner's machine.
 */
val windowsAppLibs by tasks.registering(Sync::class) {
    description = "Stage the Windows-x64 runtime classpath for the wine jpackage step."
    group = "distribution"
    from(tasks.named("jar"))
    from(windowsRuntimeClasspath)
    into(layout.buildDirectory.dir("windows/lib"))
    doLast {
        val dir = layout.buildDirectory.dir("windows/lib").get().asFile
        val jars = dir.listFiles { f: File -> f.name.endsWith(".jar") }.orEmpty()
        // Assert what the whole per-target argument is ABOUT. A Windows image with
        // a Linux skiko in it packages and installs cleanly and dies at first
        // paint; nothing before this point would have said so.
        require(jars.any { it.name.contains("skiko-awt-runtime-windows-x64") }) {
            "windowsAppLibs: no skiko-awt-runtime-windows-x64 jar staged — the Windows image would have no renderer"
        }
        require(jars.none { it.name.contains("skiko-awt-runtime-linux") }) {
            "windowsAppLibs: a LINUX skiko jar was staged into the Windows layout"
        }
        logger.lifecycle("windowsAppLibs: ${jars.size} jars staged into $dir")
    }
}

/**
 * The end-to-end updater gate: runs the real [DesktopUpdater] against the real
 * channel, headlessly. Not a `Test` — it needs the daemon up and a token on disk,
 * and a unit-test suite that fails when a service is down is a suite nobody
 * trusts. scripts/release-desktop.sh runs it as its last step.
 *
 * `--args` supplies the probe's own flags; see UpdaterProbe's header.
 */
val updaterProbe by tasks.registering(JavaExec::class) {
    description = "Fetch + verify the published desktop-kt release through the updater itself."
    group = "verification"
    mainClass.set("com.silencelen.huginn.desktop.update.UpdaterProbe")
    classpath = sourceSets["main"].runtimeClasspath
}

compose.desktop {
    application {
        mainClass = "com.silencelen.huginn.desktop.MainKt"
        nativeDistributions {
            // Linux only, and Msi stays deliberately absent even now that phase 4
            // ships a Windows installer: on Linux `packageMsi` exits 0 and produces
            // NOTHING (onlyIf false), so declaring it here would make the build LOOK
            // like it ships Windows while shipping nothing. Windows comes from
            // `windowsAppLibs` + jpackage.exe under wine + makensis instead — see
            // scripts/release-desktop.sh.
            targetFormats(TargetFormat.Deb)
            packageName = "huginn-desktop-kt"
            packageVersion = appVersion
            description = "Huginn desktop client"
            vendor = "silencelen"
            linux {
                // The .desktop entry's icon (menus, docks). Generated from
                // assets/brand/raven-tile.svg by assets/brand/generate.sh.
                iconFile.set(project.file("packaging/huginn.png"))
            }
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
