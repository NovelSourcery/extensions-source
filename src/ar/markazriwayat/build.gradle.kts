import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Markazriwayat"
    versionCode = 9
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://markazriwayat.com"
    }

    deeplink {
        host("markazriwayat.com")
        path("/..*")
    }
}
