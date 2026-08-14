import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Honeyfeed"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.honeyfeed.fm"
    }

    deeplink {
        host("www.honeyfeed.fm")
        path("/novels/..*")
    }
}
