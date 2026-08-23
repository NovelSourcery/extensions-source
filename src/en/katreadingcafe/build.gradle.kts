import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "KatReadingCafe"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "en"
        baseUrl = "https://katreadingcafe.com"
    }

    deeplink {
        host("katreadingcafe.com")
        path("/series/..*")
    }
}
