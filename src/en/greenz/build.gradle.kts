import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Greenz"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://greenz.com"
        lang = "en"
    }

    deeplink {
        host("greenz.com")
        path("/..*")
    }
}
