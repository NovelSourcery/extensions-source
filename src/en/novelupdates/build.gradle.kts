import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Novel Updates"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://www.novelupdates.com"
    }

    deeplink {
        host("www.novelupdates.com")
        path("/series/..*")
    }
}

dependencies {
    implementation(project(":lib:siteparsers"))
}
