import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "LightNovelFR"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "fr"
        baseUrl = "https://lightnovelfr.com"
    }

    deeplink {
        host("lightnovelfr.com")
        path("/series/..*")
    }
}
