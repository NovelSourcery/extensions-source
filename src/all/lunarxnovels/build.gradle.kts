import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "LunarX Novels"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    listOf("all", "en", "id", "tl").forEach { language ->
        source {
            lang = language
            baseUrl = "https://lunarx.to"
        }
    }

    deeplink {
        host("lunarx.to")
        path("/novel/..*")
    }
}
