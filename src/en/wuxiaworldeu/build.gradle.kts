import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "ReadNovelEU"
    versionCode = 8
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://readnovel.eu"
        id = 2133254182037727506L
    }

    deeplink {
        host("readnovel.eu")
        host("wuxiaworld.eu")
        host("www.wuxiaworld.eu")
        host("wuxia.click")
        path("/novel/..*")
        path("/..*")
    }
}
