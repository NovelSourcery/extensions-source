import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelTime"
    versionCode = 2
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://novltime.com"
    }

    deeplink {
        host("novltime.com")
        path("/ar/novel/..*")
    }
}
