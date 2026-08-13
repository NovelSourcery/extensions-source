import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "PanchoTranslations"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "es"
        baseUrl = "https://panchonovels.online"
    }

    deeplink {
        host("panchonovels.online")
        path("/novel/..*")
    }
}
