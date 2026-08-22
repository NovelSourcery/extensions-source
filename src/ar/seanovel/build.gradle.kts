import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "SeaNovel"
    versionCode = 5
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://seanovel.org"
    }

    deeplink {
        host("seanovel.org")
        path("/novels/..*")
    }
}
