import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Arab Novels"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "Arab Novels"
        lang = "ar"
        baseUrl = "https://arabnovels.com"
    }

    deeplink {
        path("/novels/.+")
    }
}
