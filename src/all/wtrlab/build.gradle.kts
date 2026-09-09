import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(ns.plugins.extension)
}

keiyoushi {
    name = "WTR-LAB"
    versionCode = 12
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl = "https://wtr-lab.com"
    }

    source {
        lang = "tr"
        baseUrl = "https://wtr-lab.com"
    }

    source {
        lang = "es"
        baseUrl = "https://wtr-lab.com"
    }

    source {
        lang = "id"
        baseUrl = "https://wtr-lab.com"
    }

    source {
        lang = "pt"
        baseUrl = "https://wtr-lab.com"
    }

    source {
        lang = "th"
        baseUrl = "https://wtr-lab.com"
    }

    source {
        lang = "ru"
        baseUrl = "https://wtr-lab.com"
    }

    deeplink {
        host("wtr-lab.com")
        path("/../novel/..*")
        path("/../serie-..*")
    }
}

dependencies {
    implementation(project(":lib:chapterutils"))
}
