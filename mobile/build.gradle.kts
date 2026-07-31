// Every plugin any module uses must be declared HERE with `apply false`, even the
// ones only :core applies. Declaring the multiplatform plugin solely in
// core/build.gradle.kts fails the build: the Kotlin Gradle plugin is already on
// the buildscript classpath via kotlin.android, and a second, undeclared load of
// it in a subproject is rejected. Root declaration puts one copy on the
// classpath and lets each module opt in.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.google.services) apply false
}
