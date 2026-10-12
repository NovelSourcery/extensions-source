import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "XNovel"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://xnovel.me"
    }

    // Novel pages are /<numeric id>-<slug>; pathPattern has no character classes.
    deeplink {
        path("/1.*")
        path("/2.*")
        path("/3.*")
        path("/4.*")
        path("/5.*")
        path("/6.*")
        path("/7.*")
        path("/8.*")
        path("/9.*")
    }
}
