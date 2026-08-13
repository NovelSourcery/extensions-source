import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Azora"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "ar"
        baseUrl = "https://azoramoon.com"
    }

    deeplink {
        host("azoramoon.com")
        path("/novel/..*")
    }
}
