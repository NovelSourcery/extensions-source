import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WuxiaClick"
    versionCode = 8
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://wuxia.click"
        id = 4007327599712723254L
    }

    deeplink {
        host("wuxia.click")
        path("/novel/..*")
    }
}
