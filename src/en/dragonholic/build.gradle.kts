import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Dragonholic"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://dragonholictranslations.com"
    }

    deeplink {
        host("dragonholictranslations.com")
        path("/novel/..*")
    }
}
