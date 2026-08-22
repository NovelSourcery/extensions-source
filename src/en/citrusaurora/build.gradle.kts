import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "CitrusAurora"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://citrusaurora.com"
    }

    deeplink {
        host("citrusaurora.com")
        path("/series/..*")
    }
}
