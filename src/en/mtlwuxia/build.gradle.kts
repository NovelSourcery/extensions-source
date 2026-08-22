import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "MTL Wuxia"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://mtlwuxia.com"
    }

    deeplink {
        host("mtlwuxia.com")
        path("/novel/..*")
    }
}
