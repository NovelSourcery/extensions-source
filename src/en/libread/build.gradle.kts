import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "LibRead"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://libread.com"
    }

    deeplink {
        host("libread.com")
        path("/libread/..*")
    }
}
