import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "AllNovelFull"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://novgo.net"
    }

    deeplink {
        host("novgo.net")
        path("/{slug}")
        path("/{slug}/chapter-..*")
    }
}
