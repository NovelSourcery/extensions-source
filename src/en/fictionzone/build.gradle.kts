import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Fiction Zone"
    versionCode = 11
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://fictionzone.net"
    }

    deeplink {
        host("fictionzone.net")
        path("/novel/..*")
        path("/omniportal/..*")
    }
}
