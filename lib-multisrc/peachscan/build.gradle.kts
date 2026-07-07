import keiyoushi.gradle.extensions.baseVersionCode
import keiyoushi.gradle.extensions.libVersion

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 9
libVersion = "1.4"

dependencies {
    implementation(project(":lib:zipinterceptor"))
}
