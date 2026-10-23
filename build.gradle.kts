// The plugins of the whole build, loaded once here. The build's own plugins (build-logic) apply
// Android's and Compose's, the app applies Roborazzi's, and Kotlin's only pins the version that
// AGP's built-in Kotlin uses.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.roborazzi) apply false
}
