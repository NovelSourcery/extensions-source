import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Novel-Bin (com)"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://novel-bin.com"
    }

    deeplink {
        host("novel-bin.com")
        path("/..*")
    }
}
