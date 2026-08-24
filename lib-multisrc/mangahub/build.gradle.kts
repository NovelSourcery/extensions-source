plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 34
    libVersion = "1.4"
}

dependencies {
    //noinspection UseTomlInstead
    implementation("org.brotli:dec:0.1.2")
}
