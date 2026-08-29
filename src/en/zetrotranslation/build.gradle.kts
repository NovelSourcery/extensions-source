import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Zetro Translation"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://zetrotranslation.com"
    }

    deeplink {
        host("zetrotranslation.com")
        path("/novel/..*")
    }
}
