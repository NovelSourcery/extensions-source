import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Novel Archive"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://novelarchive.st"
    }

    deeplink {
        host("novelarchive.st")
        host("novelarchive.cc")
        path("/..*")
    }
}
