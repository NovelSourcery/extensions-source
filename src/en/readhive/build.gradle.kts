import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "ReadHive"
    theme = "nzoseries"
    versionCode = 1
    contentWarning = ContentWarning.MIXED

    source {
        lang = "en"
        baseUrl = "https://readhive.org"
    }
}
