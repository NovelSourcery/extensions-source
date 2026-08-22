import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelTR"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "tr"
        baseUrl = "https://noveltr.com"
    }

    deeplink {
        host("noveltr.com")
        path("/series/..*")
    }
}
