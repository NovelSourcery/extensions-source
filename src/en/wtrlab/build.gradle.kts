import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WTR-LAB"
    versionCode = 11
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://wtr-lab.com"
    }

    deeplink {
        host("wtr-lab.com")
        path("/en/novel/..*")
        path("/en/serie-..*")
    }
}

dependencies {
    implementation(project(":lib:chapterutils"))
}
