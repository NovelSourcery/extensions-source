import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Novel Ping"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://novelping.com"
        id = 452096882710498296L
    }

    deeplink {
        host("novelping.com")
        host("novelarrow.com")
        path("/novel/..*")
        path("/book/..*")
    }
}
