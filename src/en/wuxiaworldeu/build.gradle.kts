import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WuxiaWorldEU"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.wuxiaworld.eu"
    }

    deeplink {
        host("wuxiaworld.eu")
        host("www.wuxiaworld.eu")
        path("/novel/..*")
        path("/..*")
    }
}
