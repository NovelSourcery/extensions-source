import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Reigokai Translations"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://reigokaitranslations.com"
    }

    deeplink {
        host("reigokaitranslations.com")
        path("/..*")
    }
}
