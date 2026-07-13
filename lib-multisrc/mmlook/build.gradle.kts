import io.github.keiyoushi.gradle.internal.extensions.baseVersionCode
import io.github.keiyoushi.gradle.internal.extensions.libVersion

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 2
libVersion = "1.4"

dependencies {
    implementation(project(":lib:unpacker"))
}
