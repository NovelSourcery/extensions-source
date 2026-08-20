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
        // Pulls jsoup 1.11.2 transitively, 11 versions behind the project's 1.22.2 (see
        // gradle/libs.versions.toml) and binary-incompatible with it (e.g. NodeVisitor.traverse
        // changed signature) - bundling both into one extension APK causes
        // IncompatibleClassChangeError at runtime wherever the two versions' classes cross.
        exclude(group = "org.jsoup")
    }
}
