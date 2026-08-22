import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Novel Archive"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://novelarchive.cc"
    }

    deeplink {
        host("novelarchive.cc")
        path("/..*")
    }
}
