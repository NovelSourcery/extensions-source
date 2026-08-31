import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "ReChapters"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://www.rechapters.com"
        lang = "en"
    }

    deeplink {
        host("www.rechapters.com")
        path("/book/..*")
    }
}
