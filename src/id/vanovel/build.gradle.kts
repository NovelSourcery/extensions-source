import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Vanovel"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "id"
        baseUrl = "https://vanovel.com"
    }

    deeplink {
        host("vanovel.com")
        path("/novel/..*")
    }
}
