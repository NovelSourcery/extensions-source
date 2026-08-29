import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelLib"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "fictioneer"

    source {
        lang = "en"
        baseUrl = "https://novelib.com"
    }

    deeplink {
        host("novelib.com")
        path("/story/..*")
    }
}
