import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WBNovel"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "id"
        baseUrl = "https://wbnovel.com"
    }

    deeplink {
        host("wbnovel.com")
        path("/novel/..*")
    }
}
