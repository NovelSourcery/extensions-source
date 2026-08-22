import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Cyrisia"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://cyrisia.com"
        lang = "en"
    }

    deeplink {
        host("cyrisia.com")
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:cookieinterceptor"))
}
