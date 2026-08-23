import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelHall"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://novelhall.com"
    }

    deeplink {
        host("novelhall.com")
        path("/..*")
    }
}
