import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "ArNovel"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "ar"
        baseUrl = "https://ar-no.com"
    }

    deeplink {
        host("ar-no.com")
        path("/novel/..*")
    }
}
