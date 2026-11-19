import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Novel-Bin (com)"
    versionCode = 16
    contentWarning = ContentWarning.MIXED
    theme = "novelbin"

    source {
        lang = "en"
        baseUrl = "https://novel-bin.com"
    }
}
