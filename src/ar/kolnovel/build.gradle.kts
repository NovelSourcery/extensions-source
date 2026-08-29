import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Kol Novel"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "ar"
        baseUrl = "https://kolnovel.com"
    }

    deeplink {
        host("kolnovel.com")
        path("/..*")
    }
}
