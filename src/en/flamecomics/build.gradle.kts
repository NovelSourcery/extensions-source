import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "FlameComics"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://flamecomics.xyz"
    }

    deeplink {
        host("flamecomics.xyz")
        path("/novel/..*")
    }
}
