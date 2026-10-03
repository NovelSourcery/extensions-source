import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Eternalune"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://eternalune.com"
    }

    deeplink {
        host("eternalune.com")
        path("/novel/..*")
    }
}
