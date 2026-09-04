import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Dragonholic"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://dragonholictranslations.com"
    }

    deeplink {
        host("dragonholictranslations.com")
        path("/series/..*")
    }
}

dependencies {
    implementation(project(":lib:chapterutils"))
}
