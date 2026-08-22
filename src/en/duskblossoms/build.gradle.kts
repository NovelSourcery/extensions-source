import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "DuskBlossoms"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://duskblossoms.com"
    }

    deeplink {
        host("duskblossoms.com")
        path("/novel/..*")
    }
}
