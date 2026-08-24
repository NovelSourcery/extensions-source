import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "KuuPress"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://kuupress.com"
    }

    deeplink {
        host("kuupress.com")
        path("/read/..*")
        path("/novel/..*")
    }
}
