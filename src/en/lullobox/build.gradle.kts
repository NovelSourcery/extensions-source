import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "LulloBox"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://lullobox.com"
    }

    deeplink {
        host("lullobox.com")
        path("/novel/..*")
    }
}
