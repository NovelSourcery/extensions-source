import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "KDT Novels"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "en"
        baseUrl = "https://kdtnovels.net"
    }

    deeplink {
        host("kdtnovels.net")
        path("/series/..*")
    }
}
