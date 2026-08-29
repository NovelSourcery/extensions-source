import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Lnori"
    versionCode = 8
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        id = 787226943935256051L
        lang = "en"
        baseUrl = "https://lnori.com"
    }

    deeplink {
        host("lnori.com")
        path("/series/..*")
    }
}
