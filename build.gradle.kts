// Plugins are declared once here (not applied) so every module shares one
// classloader and one Kotlin Gradle Plugin version. AGP 9 compiles Kotlin in
// Android modules itself (built-in Kotlin) using that same KGP.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
