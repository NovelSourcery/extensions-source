import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Divine Dao Library"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "fictioneer"

    source {
        lang = "en"
        baseUrl = "https://www.divinedaolibrary.com"
    }

    deeplink {
        host("www.divinedaolibrary.com")
        path("/story/..*")
    }
}
