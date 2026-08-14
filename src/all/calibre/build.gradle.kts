import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Calibre"
    versionCode = 1
    contentWarning = ContentWarning.SAFE

    source {
        lang = "all"
        baseUrl { custom("http://192.168.1.10:8080") }
    }
}
