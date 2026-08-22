import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "PenguinSquad"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://penguin-squad.com"
    }

    deeplink {
        host("penguin-squad.com")
        path("/novels/..*")
    }
}
