import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Fenrirealm"
    versionCode = 12
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://fenrirealm.com"
    }

    deeplink {
        host("fenrirealm.com")
        path("/series/..*")
    }
}
