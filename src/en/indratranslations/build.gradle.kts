import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "IndraTranslations"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        name = "Indra Translations"
        baseUrl = "https://indratranslations.com"
        lang = "en"
    }

    deeplink {
        path("/..*")
    }
}
