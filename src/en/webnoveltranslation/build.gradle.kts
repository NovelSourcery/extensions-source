import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WNTL"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        id = 6412985718349558014L
        lang = "en"
        baseUrl = "https://wntl.net"
    }

    deeplink {
        host("wntl.net")
        path("/series/..*")
    }
}
