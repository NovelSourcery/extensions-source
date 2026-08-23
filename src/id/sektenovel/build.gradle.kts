import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "SekteNovel"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "id"
        baseUrl = "https://sektenovel.web.id"
    }

    deeplink {
        host("sektenovel.web.id")
        path("/series/..*")
    }
}
