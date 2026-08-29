import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "LnCrawler"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "all"
        baseUrl = "https://lncrawler.monster"
    }

    deeplink {
        host("lncrawler.monster")
        path("/novels/..*")
    }
}
