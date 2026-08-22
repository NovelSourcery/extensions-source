import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WebNovelOku"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "tr"
        baseUrl = "https://www.webnoveloku.com"
    }

    deeplink {
        host("www.webnoveloku.com")
        path("/manga/..*")
    }
}
