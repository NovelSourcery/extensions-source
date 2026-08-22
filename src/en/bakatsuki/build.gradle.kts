import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Baka-Tsuki"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.baka-tsuki.org"
    }

    deeplink {
        host("www.baka-tsuki.org")
        path("/project/..*")
    }
}
