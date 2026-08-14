import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "AllNovel"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://allnovel.org"
    }

    deeplink {
        host("allnovel.org")
        path("/..*")
    }
}
