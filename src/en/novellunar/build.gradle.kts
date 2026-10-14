import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelLunar"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://novellunar.com"
    }

    deeplink {
        host("novellunar.com")
        path("/novel/..*")
    }
}
