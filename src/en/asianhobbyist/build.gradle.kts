import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Asian Hobbyist"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.asianhobbyist.com"
    }

    deeplink {
        host("www.asianhobbyist.com")
        host("asianhobbyist.com")
        path("/series/..*")
    }
}
