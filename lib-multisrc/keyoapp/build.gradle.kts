plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 18
    libVersion = "1.4"
}

dependencies {
    api(project(":lib:i18n"))
}
