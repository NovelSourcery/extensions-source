import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Sufficient Velocity"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "xenforo"

    source {
        lang = "en"
        baseUrl = "https://forums.sufficientvelocity.com"
    }

    deeplink {
        host("forums.sufficientvelocity.com")
        path("/threads/..*")
    }
}
