import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NeoSekai Translations"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://www.neosekaitranslations.com"
    }

    deeplink {
        host("www.neosekaitranslations.com")
        path("/novel/..*")
    }
}
