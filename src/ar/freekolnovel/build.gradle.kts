import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Free Kol Novel"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "ar"
        baseUrl = "https://free.kolnovel.com"
    }

    deeplink {
        host("free.kolnovel.com")
        path("/series/..*")
    }
}
