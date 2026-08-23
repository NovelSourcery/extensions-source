import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "TranslationWeaver"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "en"
        baseUrl = "https://transweaver.com"
    }

    deeplink {
        host("transweaver.com")
        path("/series/..*")
    }
}
