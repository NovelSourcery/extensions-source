import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Wattpad"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.wattpad.com"
    }

    deeplink {
        host("wattpad.com")
        host("www.wattpad.com")
        path("/story/..*")
    }
}
