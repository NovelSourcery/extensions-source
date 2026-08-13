plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 2
    libVersion = "1.4"
}

dependencies {
    implementation(project(":lib:unpacker"))
}
