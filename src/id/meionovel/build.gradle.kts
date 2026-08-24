import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "MeioNovel"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "id"
        baseUrl = "https://meionovels.com"
    }

    deeplink {
        host("meionovels.com")
        path("/novel/..*")
    }
}
