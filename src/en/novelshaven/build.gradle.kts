import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelsHaven"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://novelshaventranslations.com"
    }

    deeplink {
        host("novelshaventranslations.com")
        host("novelshaven.com")
        path("/series/..*")
    }
}
