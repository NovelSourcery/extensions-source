import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Cherry Mist Cafe"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://cherrymist.cafe"
    }

    deeplink {
        host("cherrymist.cafe")
        path("/story/..*")
        path("/chapter/..*")
    }
}
