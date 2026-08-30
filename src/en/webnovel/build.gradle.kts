import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Webnovel Novels"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.webnovel.com"
    }

    deeplink {
        host("webnovel.com")
        host("www.webnovel.com")
        path("/book/..*")
    }
}
