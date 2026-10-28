import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "JadeScrolls"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://jadescrolls.com"
    }

    deeplink {
        host("jadescrolls.com")
        path("/novel/..*")
    }
}
