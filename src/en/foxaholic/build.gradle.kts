import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Foxaholic"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://www.foxaholic.com"
    }

    deeplink {
        host("www.foxaholic.com")
        path("/novel/..*")
    }
}
