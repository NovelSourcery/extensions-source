import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "KodeksLibrary"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "tr"
        baseUrl = "https://www.kodekslibrary.com"
    }

    deeplink {
        host("www.kodekslibrary.com")
        path("/series/..*")
    }
}
