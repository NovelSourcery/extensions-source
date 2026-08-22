import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WuxiaDreams"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://wuxiadreams.com"
    }

    deeplink {
        host("wuxiadreams.com")
        path("/novel/..*")
    }
}
