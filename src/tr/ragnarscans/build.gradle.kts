import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "RagnarScans"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "tr"
        baseUrl = "https://ragnarscans.com"
    }

    deeplink {
        host("ragnarscans.com")
        path("/{slug}")
    }
}
