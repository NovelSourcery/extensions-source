import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelFull"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://novelfull.com"
    }

    deeplink {
        host("novelfull.com")
        path("/..*")
    }
}
