import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "FortuneEternal"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "ko"
        baseUrl = "https://www.fortuneeternal.com"
    }

    deeplink {
        host("www.fortuneeternal.com")
        path("/novel/..*")
    }
}
