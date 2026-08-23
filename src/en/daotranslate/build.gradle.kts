import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Dao Translate"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "en"
        baseUrl = "https://daotranslate.com"
    }

    deeplink {
        host("daotranslate.com")
        path("/series/..*")
    }
}
