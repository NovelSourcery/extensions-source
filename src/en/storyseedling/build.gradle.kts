import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "StorySeedling"
    theme = "nzoseries"
    versionCode = 7
    contentWarning = ContentWarning.SAFE

    source {
        lang = "en"
        baseUrl = "https://storyseedling.com"
    }
}
