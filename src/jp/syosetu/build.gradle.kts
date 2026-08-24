import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    contentWarning = ContentWarning.SAFE

    name = "小説家になろう"
    versionCode = 3
    theme = "syosetu"

    source {
        lang = "ja"
        baseUrl = "https://ncode.syosetu.com"
    }
}
