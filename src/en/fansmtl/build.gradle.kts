import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Fans MTL"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "readwn"

    source {
        lang = "en"
        baseUrl = "https://www.fanmtl.com"
    }

    deeplink {
        host("www.fanmtl.com")
        path("/novel/..*")
    }
}
