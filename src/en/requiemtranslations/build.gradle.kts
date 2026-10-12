import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Requiem Translations"
    versionCode = 9
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://requiemtls.com"
    }

    deeplink {
        host("requiemtls.com")
        path("/series/..*")
        path("/..*-episode-..*")
    }
}
