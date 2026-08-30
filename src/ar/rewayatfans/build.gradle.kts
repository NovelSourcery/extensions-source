import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Rewayat Fans"
    versionCode = 7
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://rewayatfans.com"
    }

    deeplink {
        host("rewayatfans.com")
        path("/..*")
    }
}
