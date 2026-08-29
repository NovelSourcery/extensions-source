import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelLucky"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "th"
        baseUrl = "https://novel-lucky.com"
    }

    deeplink {
        host("novel-lucky.com")
        path("/novel/..*")
    }
}
