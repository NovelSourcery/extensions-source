import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "CentralNovel"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "pt"
        baseUrl = "https://centralnovel.com"
    }

    deeplink {
        host("centralnovel.com")
        path("/series/..*")
    }
}
