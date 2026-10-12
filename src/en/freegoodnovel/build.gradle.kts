import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "FreeGoodNovel"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://freegoodnovel.com"
    }

    deeplink {
        host("freegoodnovel.com")
        path("/novel/..*")
    }
}
