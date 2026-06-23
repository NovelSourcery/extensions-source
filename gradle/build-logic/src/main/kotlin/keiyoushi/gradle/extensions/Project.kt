package keiyoushi.gradle.extensions

import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.accessors.dm.LibrariesForNs
import org.gradle.api.Project
import org.gradle.api.plugins.PluginManager
import org.gradle.kotlin.dsl.the

internal val Project.libs get() = the<LibrariesForLibs>()
internal val Project.ns get() = the<LibrariesForNs>()
internal val Project.kei get() = ns

internal fun Project.plugins(block: PluginManager.() -> Unit) {
    pluginManager.apply(block)
}

fun Project.spotlessTaskName() = if (providers.environmentVariable("CI").orNull != "true") "spotlessApply" else "spotlessCheck"
