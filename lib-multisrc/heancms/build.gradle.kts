import io.github.keiyoushi.gradle.internal.extensions.baseVersionCode
import io.github.keiyoushi.gradle.internal.extensions.libVersion

plugins {
    alias(kei.plugins.multisrc)
}

baseVersionCode = 29
libVersion = "1.4"

dependencies {
    api(project(":lib:i18n"))
}
