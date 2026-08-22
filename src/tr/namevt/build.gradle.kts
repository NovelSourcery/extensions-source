import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Namevt"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "tr"
        baseUrl = "https://namevt.com"
    }

    deeplink {
        host("namevt.com")
        path("/seri/..*")
    }
}
