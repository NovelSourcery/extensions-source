import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Riwayat Arab"
    versionCode = 1
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://riwayatarab.com"
    }

    deeplink {
        host("riwayatarab.com")
        path("/novel/..*")
    }
}
