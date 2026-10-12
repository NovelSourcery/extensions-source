import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "R18Novel"
    versionCode = 15
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "R18Novel"
        baseUrl = "https://r18novel.com"
        lang = "en"
    }

    deeplink {
        host("r18novel.com")
        host("www.r18novel.com")
        path("/webnovel/..*")
    }
}
