import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.gradle.tasks.PackageAndroidArtifact
import io.github.keiyoushi.gradle.api.dsl.KeiyoushiThemeExtension
import io.github.keiyoushi.gradle.internal.ExtensionMetadata
import io.github.keiyoushi.gradle.internal.GenerateLegacyKeepRulesTask
import io.github.keiyoushi.gradle.internal.SourceMetadata
import io.github.keiyoushi.gradle.internal.VALID_LIB_VERSIONS
import io.github.keiyoushi.gradle.internal.assertWithoutFlag
import io.github.keiyoushi.gradle.internal.computeSourceId
import io.github.keiyoushi.gradle.internal.extensions.alias
import io.github.keiyoushi.gradle.internal.extensions.baseVersionCode
import io.github.keiyoushi.gradle.internal.extensions.compileOnly
import io.github.keiyoushi.gradle.internal.extensions.implementation
import io.github.keiyoushi.gradle.internal.extensions.libs
import io.github.keiyoushi.gradle.internal.extensions.ns
import io.github.keiyoushi.gradle.internal.extensions.plugins
import io.github.keiyoushi.gradle.tasks.CreateExtensionJarTask
import io.github.keiyoushi.gradle.tasks.GenerateSourceInfoTask
import io.github.keiyoushi.gradle.tasks.SignExtensionJarTask
import kotlinx.serialization.json.Json
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.ExtraPropertiesExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

@Suppress("UNUSED")
class PluginExtensionLegacy : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        plugins {
            alias(libs.plugins.android.application)
            alias(libs.plugins.kotlin.serialization)

            alias(ns.plugins.android.base)
            alias(ns.plugins.spotless)
        }

        assertWithoutFlag(!extra.has("pkgNameSuffix")) { "Gradle configuration cannot contain 'pkgNameSuffix'" }
        assertWithoutFlag(extra.has("libVersion")) { "Gradle configuration must contain 'libVersion'" }
        assertWithoutFlag(libVersion in VALID_LIB_VERSIONS) {
            "libVersion $libVersion is not supported. Supported versions: $VALID_LIB_VERSIONS"
        }

        assertWithoutFlag(extName.max().code < 0x180) { "Extension name should be romanized" }

        val theme: Project? = if (extra.has("themePkg")) project(":lib-multisrc:$themePkg") else null
        if (theme != null) {
            evaluationDependsOn(theme.path)
            val themeLibVersion = theme.extensions.getByType(KeiyoushiThemeExtension::class.java).libVersion.get()
            assertWithoutFlag(themeLibVersion == libVersion) {
                "Multisrc libVersion ($themeLibVersion) and extension libVersion ($libVersion) must match."
            }
        }

        val applicationIdSuffix = "${project.parent?.name}.${project.name}"
        val lang = project.parent?.name.orEmpty()
        val versionCode = if (theme == null) extVersionCode else theme.baseVersionCode + overrideVersionCode
        val versionName = "$libVersion.$versionCode"
        val filename = "tsundoku-$applicationIdSuffix-v$versionName"

        android {
            namespace = "eu.kanade.tachiyomi.novelextension"

            sourceSets {
                named("main") {
                    manifest.srcFile(rootProject.file("common/AndroidManifest.xml"))
                    java.directories.clear()
                    java.directories.add("src")
                    kotlin.directories.clear()
                    kotlin.directories.add("src")
                    res.directories.clear()
                    res.directories.add("res")
                    assets.directories.clear()
                    assets.directories.add("assets")
                }
            }

            defaultConfig {
                this.applicationIdSuffix = applicationIdSuffix
                this.versionCode = versionCode
                this.versionName = versionName
                base {
                    archivesName.set(filename)
                }
                assertWithoutFlag(extClass.startsWith(".")) { "'extClass' must start with '.'" }
                manifestPlaceholders += mapOf(
                    "appName" to "Tsundoku: $extName",
                    "extClass" to extClass,
                    "nsfw" to if (isNsfw) 1 else 0,
                    "tachiyomix.name" to extName,
                    "tachiyomix.contentWarning" to if (isNsfw) 2 else 0,
                    "tachiyomix.extensionLib" to libVersion,
                )
                if (theme != null && baseUrl.isNotEmpty()) {
                    val split = baseUrl.split("://")
                    assertWithoutFlag(split.size == 2) { "'baseUrl' must be in the format of 'https://example.com'" }
                    val path = split[1].split("/")
                    manifestPlaceholders += mapOf(
                        "SOURCEHOST" to path[0],
                        "SOURCESCHEME" to split[0],
                    )
                }
            }

            lint {
                checkReleaseBuilds = false
            }

            signingConfigs {
                create("release") {
                    storeFile = rootProject.file("signingkey.jks")
                    storePassword = providers.environmentVariable("KEY_STORE_PASSWORD").orNull
                    keyAlias = providers.environmentVariable("ALIAS").orNull
                    keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
                }
            }

            buildTypes {
                named("release") {
                    signingConfig = if (rootProject.file("signingkey.jks").exists()) {
                        signingConfigs.getByName("release")
                    } else {
                        signingConfigs.getByName("debug")
                    }
                    isMinifyEnabled = false
                    @Suppress("UnstableApiUsage")
                    vcsInfo.include = false
                }
            }

            dependenciesInfo {
                includeInApk = false
            }

            buildFeatures {
                buildConfig = true
            }

            packaging {
                resources.excludes.add("kotlin-tooling-metadata.json")
            }
        }

        val sourceInfoTask = tasks.register<GenerateSourceInfoTask>("generateSourceInfo") {
            this.outputFile.set(layout.buildDirectory.file("keiyoushi-source-info.json"))
            this.content.set(
                Json.encodeToString(
                    ExtensionMetadata(
                        module = applicationIdSuffix,
                        theme = if (theme != null) themePkg else null,
                        packageName = "eu.kanade.tachiyomi.novelextension.$applicationIdSuffix",
                        name = extName,
                        versionCode = versionCode,
                        versionName = versionName,
                        extensionLib = libVersion,
                        contentWarning = if (isNsfw) 3 else 1,
                        sources = listOf(
                            SourceMetadata(
                                id = computeSourceId(extName, lang),
                                name = extName,
                                lang = lang,
                                baseUrl = baseUrl,
                            ),
                        ),
                    ),
                ),
            )
        }
        val providedClasspath = configurations.create("extensionProvidedClasspath") {
            isCanBeConsumed = false
            isCanBeResolved = true
            extendsFrom(configurations.getByName("compileOnly"))
        }

        val signingConfig = extensions.getByType(ApplicationExtension::class.java).signingConfigs
            .getByName(if (rootProject.file("signingkey.jks").exists()) "release" else "debug")

        androidComponents {
            val bootClasspath = sdkComponents.bootClasspath

            onVariants { variant ->
                val variantName = variant.name.replaceFirstChar { it.uppercase() }

                @Suppress("UnstableApiUsage")
                val keepRules = variant.sources.keepRules
                if (keepRules != null) {
                    val task = tasks.register<GenerateLegacyKeepRulesTask>("generate${variantName}KeepRules") {
                        this.applicationId.set(variant.applicationId)
                        this.className.set(this@with.extClass)
                    }
                    keepRules.addGeneratedSourceDirectory(task) { it.outputDir }
                }

                variant.sources.manifests.addStaticManifestFile("AndroidManifest.xml")

                if (variant.buildType == "release") {
                    val externalLibs = providedClasspath.incoming.artifactView {
                        attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
                    }.files

                    val createTask = tasks.register<CreateExtensionJarTask>("create${variantName}ExtensionJar") {
                        libraryClasspath.from(externalLibs, bootClasspath)
                        manifestFile.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                        apkDir.set(variant.artifacts.get(SingleArtifact.APK))
                        outputJar.set(layout.buildDirectory.file("intermediates/extension_jar/${variant.name}/unsigned.jar"))
                    }

                    variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
                        .use(createTask)
                        .toGet(
                            ScopedArtifact.CLASSES,
                            CreateExtensionJarTask::jars,
                            CreateExtensionJarTask::dirs,
                        )

                    val signTask = tasks.register<SignExtensionJarTask>("sign${variantName}ExtensionJar") {
                        inputJar.set(createTask.flatMap { it.outputJar })
                        signingConfig.storeFile?.let { keystore.from(it) }
                        storePassword.set(signingConfig.storePassword.orEmpty())
                        keyAlias.set(signingConfig.keyAlias.orEmpty())
                        keyPassword.set(signingConfig.keyPassword.orEmpty())
                        minSdkVersion.set(ns.versions.android.sdk.min.map { it.toInt() })
                        outputJar.set(layout.buildDirectory.file("outputs/jar/${variant.name}/$filename.jar"))
                    }

                    tasks.matching { it.name == "assemble$variantName" }
                        .configureEach { dependsOn(signTask) }
                }
            }
        }

        dependencies {
            if (theme != null) implementation(theme) // Overrides core launcher icons
            implementation(project(":core"))
            compileOnly(libs.bundles.common)
            compileOnly(if (libVersion == "1.6") libs.tachiyomi.lib.v16 else libs.tachiyomi.lib.v14)
        }

        afterEvaluate {
            tasks.named("assembleRelease").configure { dependsOn(sourceInfoTask) }

            tasks.withType<PackageAndroidArtifact>().configureEach {
                createdBy.set("")
                doFirst {
                    appMetadata.asFile.orNull?.writeText("")
                }
            }
        }
    }
}

private fun Project.android(block: ApplicationExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.androidComponents(block: ApplicationAndroidComponentsExtension.() -> Unit) {
    extensions.configure(block)
}

private fun Project.base(block: BasePluginExtension.() -> Unit) {
    extensions.configure(block)
}

private val Project.extName: String
    get() = extra.get("extName") as String

private val Project.libVersion: String
    get() = extra.get("libVersion") as String

private val Project.extVersionCode: Int
    get() = extra.get("extVersionCode") as Int

private val Project.extClass: String
    get() = extra.get("extClass") as String

private val Project.isNsfw: Boolean
    get() = extra.getOrNull("isNsfw") == true

private val Project.baseUrl: String
    get() = (extra.getOrNull("baseUrl") as String?).orEmpty()

private val Project.overrideVersionCode: Int
    get() = extra.get("overrideVersionCode") as Int

private val Project.themePkg: String
    get() = extra.get("themePkg") as String

private fun ExtraPropertiesExtension.getOrNull(name: String) = if (has(name)) get(name) else null
