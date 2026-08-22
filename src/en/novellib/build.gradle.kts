import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelLib"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.novellib.online"
    }

    deeplink {
        host("www.novellib.online")
        path("/novel/..*")
    }
}
