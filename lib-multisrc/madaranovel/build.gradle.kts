plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 3
    libVersion = "1.4"
}

dependencies {
    implementation(project(":lib:chapterutils"))
}
