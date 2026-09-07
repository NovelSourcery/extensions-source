import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Galaxy Novels"
    versionCode = 7
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://galaxynovels.com"
    }

    deeplink {
        host("galaxynovels.com")
        path("/..*")
    }
}
