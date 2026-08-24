import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Hiraeth Translation"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://hiraethtranslation.com"
    }

    deeplink {
        host("hiraethtranslation.com")
        path("/novel/..*")
    }
}
