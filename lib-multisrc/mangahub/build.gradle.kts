import keiyoushi.gradle.extensions.baseVersionCode
import keiyoushi.gradle.extensions.libVersion

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 34
libVersion = "1.4"

dependencies {
    //noinspection UseTomlInstead
    implementation("org.brotli:dec:0.1.2")
}
