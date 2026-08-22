import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "MassNovel"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "fr"
        baseUrl = "https://massnovel.fr"
    }

    deeplink {
        host("massnovel.fr")
        path("/novel/..*")
    }
}
