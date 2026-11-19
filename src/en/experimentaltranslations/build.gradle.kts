import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Experimental Translations"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://experimentaltranslations.com"
    }

    deeplink {
        host("experimentaltranslations.com")
        host("experimentaltranslations.wordpress.com")
        path("/..*")
    }
}
