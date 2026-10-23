plugins {
    id("klangbild.multiplatform")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.text)
            api(libs.kotlinx.datetime)
        }
        wasmJsMain.dependencies {
            // The rules of every time zone, which the browser doesn't give kotlinx-datetime.
            implementation(npm("@js-joda/timezone", libs.versions.jsJodaTimezone.get()))
        }
    }
}
