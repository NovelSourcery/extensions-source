import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Riwyat"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "ar"
        baseUrl = "https://cenele.com"
    }

    deeplink {
        host("cenele.com")
        path("/..*")
    }
}
