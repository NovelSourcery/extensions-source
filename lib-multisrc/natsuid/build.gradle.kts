import io.github.keiyoushi.gradle.internal.extensions.baseVersionCode
import io.github.keiyoushi.gradle.internal.extensions.libVersion

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 2
libVersion = "1.4"

dependencies {
    compileOnly("com.squareup.okhttp3:okhttp-brotli:5.0.0-alpha.11")
}
