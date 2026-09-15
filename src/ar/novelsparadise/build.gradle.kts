import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "NovelsParadise"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    theme = "lightnovelwpnovel"

    source {
        lang = "ar"
        baseUrl {
            mirrors(
                "https://www.novelsparadise.site",
                "https://novelsparadise.site",
            )
        }
    }

    deeplink {
        host("novelsparadise.site")
        host("www.novelsparadise.site")
        path("/np-light/series/..*")
    }
}
