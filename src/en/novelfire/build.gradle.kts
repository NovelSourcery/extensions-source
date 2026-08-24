import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Novel Phoenix"
    versionCode = 13
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://novelphoenix.com"
        id = 7165539527173321330L
    }

    deeplink {
        host("novelphoenix.com")
        path("/novel/..*")
    }
}

dependencies {
    implementation(project(":lib:chapterutils"))
}
