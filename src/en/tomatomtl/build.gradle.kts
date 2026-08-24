import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "TomatoMTL"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://tomatomtl.com"
    }

    deeplink {
        host("tomatomtl.com")
        path("/book/..*")
        path("/garden/..*")
    }
}
