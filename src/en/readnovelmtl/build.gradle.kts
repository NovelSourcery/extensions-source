import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "ReadNovelMtl"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://readnovelmtl.com"
    }

    deeplink {
        host("readnovelmtl.com")
        path("/novel/..*")
    }
}
