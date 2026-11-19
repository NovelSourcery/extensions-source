import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Lupus-sensei Translations"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.lupus-sensei.com"
    }

    deeplink {
        host("www.lupus-sensei.com")
        path("/p/..*")
        path("/20..*")
    }
}
