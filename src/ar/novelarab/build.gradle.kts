import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Novel Arab"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "ar"
        baseUrl = "https://novelarab.com"
    }

    deeplink {
        host("novelarab.com")
        path("/novel/..*")
    }
}
