import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Crimson Scrolls"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://crimsonscrolls.net"
    }

    deeplink {
        host("crimsonscrolls.net")
        path("/novel/..*")
    }
}
