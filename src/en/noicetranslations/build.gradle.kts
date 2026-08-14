import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NoiceTranslations"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://noicetranslations.com"
    }

    deeplink {
        host("noicetranslations.com")
        path("/manga/..*")
        path("/novel/..*")
    }
}
