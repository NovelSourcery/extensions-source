import keiyoushi.gradle.extensions.baseVersionCode
import keiyoushi.gradle.extensions.libVersion

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 17
libVersion = "1.4"

dependencies {
    api(project(":lib:i18n"))
}
