import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WordExcerpt"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://wordexcerpt.com"
    }

    deeplink {
        host("wordexcerpt.com")
        path("/..*")
    }
}
