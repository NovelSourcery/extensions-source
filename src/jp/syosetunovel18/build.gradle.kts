import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    contentWarning = ContentWarning.NSFW

    name = "小説家になろう (R18)"
    versionCode = 2
    theme = "syosetu"

    source {
        lang = "ja"
        baseUrl = "https://novel18.syosetu.com"
    }
}
