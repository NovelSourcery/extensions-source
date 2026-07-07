plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)

    alias(kei.plugins.android.base)
    alias(kei.plugins.spotless)
}

android {
    namespace = "keiyoushi.core"

    buildFeatures {
        resValues = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // :core targets the 1.6 lib line specifically for awaitSuccess - it doesn't touch
    // RefreshContext, so this is independent of what individual extensions compile against.
    compileOnly(libs.bundles.common16)

    testImplementation(libs.bundles.common16)
    testImplementation(libs.junit)
}
