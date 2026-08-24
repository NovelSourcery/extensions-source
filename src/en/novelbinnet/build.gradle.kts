import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "Novel-Bin (net)"
    versionCode = 2
    contentWarning = ContentWarning.NSFW
    theme = "readnovelfull"

    source {
        lang = "en"
        baseUrl = "https://novel-bin.net"
    }

    deeplink {
        host("novel-bin.net")
        path("/novel-bin/..*")
    }
}
