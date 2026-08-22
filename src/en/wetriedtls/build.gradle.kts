import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WeTried Translations"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://wetriedtls.com"
    }

    deeplink {
        host("wetriedtls.com")
        path("/series/..*")
    }
}
