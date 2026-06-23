dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../libs.versions.toml"))
        }
        create("ns") {
            from(files("../ns.versions.toml"))
        }
        create("kei") {
            from(files("../ns.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
