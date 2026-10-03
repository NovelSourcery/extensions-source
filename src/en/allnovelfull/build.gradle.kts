import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Novgo"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://novgo.net"
        id = 3273374795580060030L
    }

    deeplink {
        host("novgo.net")
        path("/..*")
    }
}
