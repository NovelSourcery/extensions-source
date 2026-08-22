import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Azora"
    versionCode = 5
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://azorafly.com"
    }

    deeplink {
        host("azorafly.com")
        path("/series/..*")
    }
}
