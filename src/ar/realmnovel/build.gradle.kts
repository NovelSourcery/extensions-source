import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "RealmNovel"
    versionCode = 1
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://realmnovel.com"
    }

    deeplink {
        host("realmnovel.com")
        path("/..*")
    }
}
