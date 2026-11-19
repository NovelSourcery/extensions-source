import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Novel Bin (xyz)"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://novelbin.xyz"
    }

    deeplink {
        host("novelbin.xyz")
        path("/novel/..*")
        path("/read/..*")
    }
}
