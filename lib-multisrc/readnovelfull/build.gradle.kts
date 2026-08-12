plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 10
    libVersion = "1.6"
}

dependencies {
    implementation(project(":lib:chapterutils"))
}
