import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Atsumaru Novels"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://atsu.moe"
    }

    deeplink {
        host("atsu.moe")
        path("/novel/..*")
    }
}
