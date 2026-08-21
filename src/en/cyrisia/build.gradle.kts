import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Cyrisia"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        baseUrl = "https://cyrisia.com"
        lang = "en"
    }

    deeplink {
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:cookieinterceptor"))
}
