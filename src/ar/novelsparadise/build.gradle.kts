import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelsParadise"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "ar"
        baseUrl = "https://novelsparadise.site"
    }

    deeplink {
        host("novelsparadise.site")
        path("/series/..*")
    }
}
