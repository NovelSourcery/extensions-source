import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "EKitaplar"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "tr"
        baseUrl = "https://e-kitaplar.com"
    }

    deeplink {
        host("e-kitaplar.com")
        path("/novel/..*")
    }
}
