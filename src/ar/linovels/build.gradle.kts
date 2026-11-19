import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Linovels"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "Linovels"
        lang = "ar"
        baseUrl = "https://linovels.com"
    }

    deeplink {
        path("/..*")
    }
}
