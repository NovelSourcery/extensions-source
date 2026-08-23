import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "MZ Novels"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://mznovels.com"
    }

    deeplink {
        host("mznovels.com")
        path("/novel/..*")
    }
}

dependencies {
    implementation(project(":lib:chapterutils"))
}
