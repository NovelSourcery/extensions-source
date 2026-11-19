import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Machine Sliced Bread"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://machineslicedbread.xyz"
    }

    deeplink {
        host("machineslicedbread.xyz")
        host("www.machineslicedbread.xyz")
        path("/..*")
    }
}
