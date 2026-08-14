import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Shanghai Fantasy"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://shanghaifantasy.com"
    }

    deeplink {
        host("shanghaifantasy.com")
        path("/novel/..*")
    }
}
