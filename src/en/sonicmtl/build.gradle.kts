import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Sonic MTL"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://sonicmtl.com"
    }

    deeplink {
        host("sonicmtl.com")
        path("/novel/..*")
    }
}
