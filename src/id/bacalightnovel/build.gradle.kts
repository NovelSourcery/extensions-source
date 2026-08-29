import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "BacaLightNovel"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "id"
        baseUrl = "https://bacalightnovel.co"
    }

    deeplink {
        host("bacalightnovel.co")
        path("/series/..*")
    }
}
