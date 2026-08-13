import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Konkon"
    versionCode = 5
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://konkon.ink"
    }

    deeplink {
        host("konkon.ink")
        path("/read/..*")
    }
}

dependencies {
    implementation(project(":lib:siteparsers"))
}
