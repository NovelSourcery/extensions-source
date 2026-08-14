import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelCool"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://novelcool.com"
    }

    deeplink {
        host("novelcool.com")
        path("/novel/..*")
    }
}
