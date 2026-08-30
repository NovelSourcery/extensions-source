import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Light Novel Translations"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://lightnovelstranslations.com"
    }

    deeplink {
        host("lightnovelstranslations.com")
        path("/novel/..*")
    }
}
