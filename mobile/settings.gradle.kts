pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Huginn"
include(":app")
// Platform-free logic shared with the desktop client. See core/build.gradle.kts.
include(":core")
// The shared LOOK — theme, markdown, transcript rows, terminal painter. Both
// clients render these; neither keeps a copy. See ui/build.gradle.kts.
include(":ui")
// The Compose Multiplatform desktop client. Lives in this project, not beside
// the Electron one, because its whole reason for existing is that it consumes
// :core rather than reimplementing it. ../desktop stays in service until parity.
include(":app-desktop")
