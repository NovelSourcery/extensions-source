plugins {
    alias(kei.plugins.multisrc)
}

keiyoushi {
    baseVersionCode = 23
    libVersion = "1.4"
}

dependencies {
    compileOnly("com.squareup.okhttp3:okhttp-brotli:5.0.0-alpha.11")
}
