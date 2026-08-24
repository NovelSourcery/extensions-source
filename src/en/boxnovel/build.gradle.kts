import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "BoxNovel"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://novelnice.com"
    }

    deeplink {
        host("novelnice.com")
        path("/read/..*")
        path("/novel/..*")
    }
}
