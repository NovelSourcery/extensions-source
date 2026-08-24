import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "FreeWebNovel"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://freewebnovel.com"
    }

    deeplink {
        host("freewebnovel.com")
        path("/novel/..*")
    }
}
