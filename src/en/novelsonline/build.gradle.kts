import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelsOnline"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        baseUrl = "https://www.novelsonline.org"
        lang = "en"
    }

    deeplink {
        host("novelsonline.org")
        host("www.novelsonline.org")
        path("/..*")
    }
}
