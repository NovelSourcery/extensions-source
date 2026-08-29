import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WorldNovel"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "fr"
        baseUrl = "https://world-novel.fr"
    }

    deeplink {
        host("world-novel.fr")
        path("/novel/..*")
    }
}
