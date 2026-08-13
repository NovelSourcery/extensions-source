import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "LightNovelPlus"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://lightnovelplus.com"
    }

    deeplink {
        host("lightnovelplus.com")
        path("/book/..*")
    }
}
