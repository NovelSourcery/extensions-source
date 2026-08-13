import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "EtudeTranslations"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://etudetranslations.com"
    }

    deeplink {
        host("etudetranslations.com")
        path("/novel/..*")
    }
}
