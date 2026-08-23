import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WuxiaWorld.Site"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://wuxiaworld.site"
    }

    deeplink {
        host("wuxiaworld.site")
        path("/novel/..*")
    }
}
