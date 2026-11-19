import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelPhoenix.net"
    versionCode = 2
    contentWarning = ContentWarning.MIXED
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://novelphoenix.net"
    }

    deeplink {
        host("novelphoenix.net")
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:chapterutils"))
}
