import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "System Translation"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "en"
        baseUrl = "https://systemtranslation.com"
    }

    deeplink {
        host("systemtranslation.com")
        path("/series/..*")
    }
}
