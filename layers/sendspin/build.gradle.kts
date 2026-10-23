plugins {
    id("klangbild.android.library")
}

android {
    namespace = "io.github.hazymorning.klangbild.sendspin"
}

dependencies {
    implementation(projects.json)
    // Curve25519, ChaCha20-Poly1305 and SHA-256 for Sendspin's Noise channel, through the lightweight
    // API only (no JCA provider is registered): the platform has no X25519 below API 33.
    implementation(libs.bouncycastle)
    // A message's payload is JSON.
    api(libs.kotlinx.serialization.json)
}
