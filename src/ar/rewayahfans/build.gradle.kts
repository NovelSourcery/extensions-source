import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Rewayah Fans"
    versionCode = 3
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://rewayahfans.net"
    }

    deeplink {
        host("rewayahfans.net")
        path("/..*")
    }
}
