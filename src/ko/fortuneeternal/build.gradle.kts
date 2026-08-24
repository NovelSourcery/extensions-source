import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "FortuneEternal"
    versionCode = 3
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
