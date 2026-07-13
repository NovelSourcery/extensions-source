import io.github.keiyoushi.gradle.internal.extensions.baseVersionCode
import io.github.keiyoushi.gradle.internal.extensions.libVersion

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 34
libVersion = "1.4"

dependencies {
    //noinspection UseTomlInstead
    implementation("org.brotli:dec:0.1.2")
}
