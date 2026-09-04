import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "MtlBooks"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://mtlbooks.com"
    }

    deeplink {
        host("mtlbooks.com")
        path("/novel/..*")
    }
}

dependencies {
    implementation(project(":lib:chapterutils"))
}
