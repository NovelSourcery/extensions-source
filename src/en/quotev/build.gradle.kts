import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Quotev"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://www.quotev.com"
        lang = "en"
    }

    deeplink {
        path("/..*")
    }
}
