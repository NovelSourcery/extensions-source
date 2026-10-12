import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Sainokan TLS"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://sainokantls.com"
    }

    deeplink {
        host("sainokantls.com")
        path("/novel/..*")
    }
}
