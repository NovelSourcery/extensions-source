import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Foxaholic 18+"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://18.foxaholic.com"
    }

    deeplink {
        host("18.foxaholic.com")
        path("/novel/..*")
    }
}
