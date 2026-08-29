import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    contentWarning = ContentWarning.NSFW

    name = "ノクターン (R18)"
    versionCode = 3
    theme = "syosetu"

    source {
        lang = "ja"
        baseUrl = "https://noc.syosetu.com"
    }
}
