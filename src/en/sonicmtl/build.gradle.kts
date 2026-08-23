import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Sonic MTL"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://www.sonicmtl.com"
    }

    deeplink {
        host("www.sonicmtl.com")
        host("sonicmtl.com")
        path("/novel/..*")
    }
}
