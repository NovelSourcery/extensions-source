import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "ReadNovelFull"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://readnovelfull.com"
    }

    deeplink {
        host("readnovelfull.com")
        path("/..*")
    }
}
