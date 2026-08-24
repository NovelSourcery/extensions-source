import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "LightNovelHeaven"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://lightnovelheaven.com"
    }

    deeplink {
        host("lightnovelheaven.com")
        path("/series/..*")
        path("/novel/..*")
    }
}
