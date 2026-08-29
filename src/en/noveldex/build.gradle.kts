import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelDex"
    versionCode = 7
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://noveldex.io"
    }

    deeplink {
        host("noveldex.io")
        path("/series/..*")
    }
}
