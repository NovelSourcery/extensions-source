plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 5
    libVersion = "1.6"

    // Every site type stores manga.url as a root-level slug (mangaPath = SlugPath("/")), and
    // getMangaByUrl is already implemented generically off that - only the manifest declaration
    // was missing, so pasted URLs never reached it. No host(): resolved per-extension from baseUrl.
    deeplink {
        path("/..*")
    }
}

dependencies {
    implementation(project(":lib:chapterutils"))
    implementation(project(":lib:cookieinterceptor"))
}
