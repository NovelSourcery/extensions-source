import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Golden Rest"
    versionCode = 5
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://golden.rest"
    }

    deeplink {
        host("golden.rest")
        path("/mangas/..*")
    }
}
