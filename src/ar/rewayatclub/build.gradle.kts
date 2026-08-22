import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Rewayat Club"
    versionCode = 6
    contentWarning = ContentWarning.SAFE

    source {
        lang = "ar"
        baseUrl = "https://rewayat.club"
    }

    deeplink {
        host("rewayat.club")
        path("/novel/..*")
    }
}
