import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "SleepyTranslations"
    versionCode = 3
    contentWarning = ContentWarning.SAFE
    theme = "madaranovel"

    source {
        lang = "en"
        baseUrl = "https://sleepytranslations.com"
    }

    deeplink {
        host("sleepytranslations.com")
        path("/novel/..*")
    }
}
