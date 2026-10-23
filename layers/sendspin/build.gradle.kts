plugins {
    id("klangbild.multiplatform")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.json)
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.cryptography.core)
            api(libs.cryptography.random)
        }
        // The primitives on the JVM: BouncyCastle's, which Android's own providers lack below API 33.
        androidMain.dependencies {
            implementation(libs.cryptography.provider.jdk.bc)
            implementation(libs.bouncycastle)
        }
        jvmMain.dependencies {
            implementation(libs.cryptography.provider.jdk.bc)
            implementation(libs.bouncycastle)
        }
    }
}
