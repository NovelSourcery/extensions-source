import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "FUCKNOVELPIA"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://fucknovelpia.com"
        id = 4776466639710929174L
    }

    deeplink {
        host("fucknovelpia.com")
        path("/novel/..*")
    }
}
