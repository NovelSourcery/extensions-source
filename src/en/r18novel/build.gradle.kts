import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "R18Novel"
    theme = "readnovelfull"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "R18Novel"
        baseUrl = "https://www.r18novel.com"
        lang = "en"
    }

    deeplink {
        host("www.r18novel.com")
        path("/webnovel/..*")
    }
}
