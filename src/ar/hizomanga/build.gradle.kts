import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "HizoManga"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "ar"
        baseUrl = "https://hizomanga.net"
    }

    deeplink {
        host("hizomanga.net")
        path("/novel/..*")
    }
}
