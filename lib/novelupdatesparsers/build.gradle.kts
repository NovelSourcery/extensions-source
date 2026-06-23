plugins {
    alias(kei.plugins.library)
}

android {
    namespace = "novelsourcery.lib.novelupdatesparsers"
}

dependencies {
    implementation("net.dankito.readability4j:readability4j:1.0.8")
}
