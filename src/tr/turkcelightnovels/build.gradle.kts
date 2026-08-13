import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "TurkceLightNovels"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "tr"
        baseUrl = "https://turkcelightnovels.com"
    }

    deeplink {
        host("turkcelightnovels.com")
        path("/{slug}")
    }
}
