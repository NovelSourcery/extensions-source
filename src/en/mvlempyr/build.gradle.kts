import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "MVLEMPYR"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.mvlempyr.io"
    }

    deeplink {
        host("www.mvlempyr.io")
        path("/novel/..*")
    }
}
