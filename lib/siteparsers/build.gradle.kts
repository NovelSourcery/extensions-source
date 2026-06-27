plugins {
    alias(kei.plugins.library)
}

android {
    namespace = "novelsourcery.lib.siteparsers"
}

dependencies {
    implementation("net.dankito.readability4j:readability4j:1.0.8") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx")
    }
}
