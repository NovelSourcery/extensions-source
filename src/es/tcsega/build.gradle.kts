import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "TCandSega"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "es"
        baseUrl = "https://teamchmantranslations.com"
    }

    deeplink {
        host("teamchmantranslations.com")
        path("/series/..*")
    }
}
