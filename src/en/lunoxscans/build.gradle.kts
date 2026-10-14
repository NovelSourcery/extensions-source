import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Lunox Scans"
    theme = "madaranovel"
    versionCode = 1
    contentWarning = ContentWarning.SAFE

    source {
        lang = "en"
        baseUrl = "https://lunoxscans.com"
    }

    deeplink {
        host("lunoxscans.com")
        path("/series/..*")
    }
}
