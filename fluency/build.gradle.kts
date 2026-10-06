// AGP 9 compiles Kotlin itself ("built-in Kotlin"); the Kotlin plugin is only put on the
// classpath here to pin the Kotlin compiler version to the catalog's.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
