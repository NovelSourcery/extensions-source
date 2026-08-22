import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "LazyGirlTranslations"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "en"
        baseUrl = "https://lazygirltranslations.com"
    }

    deeplink {
        host("lazygirltranslations.com")
        path("/series/..*")
    }
}
