import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "MTL Arabic"
    versionCode = 1
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://mtlarabic.com"
    }

    deeplink {
        host("mtlarabic.com")
        path("/..*")
    }
}
