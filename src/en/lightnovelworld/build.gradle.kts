import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Light Novel World"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://lightnovelworld.org"
    }

    deeplink {
        host("lightnovelworld.org")
        path("/novel/..*")
    }
}

dependencies {
    implementation(project(":lib:chapterutils"))
}
