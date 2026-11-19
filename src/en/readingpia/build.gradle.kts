import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "ReadingPia"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.readingpia.me"
    }

    deeplink {
        host("www.readingpia.me")
        host("readingpia.me")
        path("/series/..*")
    }
}
