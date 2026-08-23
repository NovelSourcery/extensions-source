import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Translatin Otaku"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://translatinotaku.net"
    }

    deeplink {
        host("translatinotaku.net")
        path("/novel/..*")
    }
}
