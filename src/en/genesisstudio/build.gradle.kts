import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "GenesisStudio"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://genesistudio.com"
    }

    deeplink {
        host("genesistudio.com")
        path("/novels/..*")
    }
}
