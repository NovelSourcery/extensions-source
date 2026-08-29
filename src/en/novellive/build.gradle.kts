import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelLive"
    versionCode = 2
    contentWarning = ContentWarning.SAFE
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://novellive.app"
    }

    deeplink {
        host("novellive.app")
        path("/book/..*")
    }
}

dependencies {
    implementation(project(":lib:chapterutils"))
}
