import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Nyx Scans"
    theme = "ikennovel"
    versionCode = 1
    contentWarning = ContentWarning.SAFE

    source {
        lang = "en"
        baseUrl = "https://nyxscans.com"
    }
}
