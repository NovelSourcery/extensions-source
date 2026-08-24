import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Wuxiabox"
    versionCode = 4
    contentWarning = ContentWarning.SAFE
    theme = "readwn"

    source {
        lang = "en"
        baseUrl = "https://wuxiabox.com"
    }

    deeplink {
        host("wuxiabox.com")
        path("/novel/..*")
    }
}
