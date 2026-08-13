import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelBookID"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "id"
        baseUrl = "https://www.novelbook.id"
    }

    deeplink {
        host("www.novelbook.id")
        path("/novel/..*")
    }
}
