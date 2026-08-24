import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Wuxia Space"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    theme = "readwn"

    source {
        lang = "en"
        baseUrl = "https://www.wuxiaspot.com"
    }

    deeplink {
        host("www.wuxiaspot.com")
        path("/novel/..*")
    }
}
