package io.github.keiyoushi.gradle.internal.extensions

import io.github.keiyoushi.gradle.api.dsl.KeiyoushiThemeExtension
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.accessors.dm.LibrariesForNs
import org.gradle.api.Project
import org.gradle.kotlin.dsl.the

internal val Project.libs get() = the<LibrariesForLibs>()

// settings.gradle.kts registers "kei" pointing at gradle/ns.versions.toml too (we don't carry a
// real kei.versions.toml), so the two resolve identically -- kept as a distinct alias, matching
// established precedent, since some call sites use one name and some the other.
internal val Project.ns get() = the<LibrariesForNs>()
internal val Project.kei get() = ns

internal var Project.baseVersionCode: Int
    get() = the<KeiyoushiThemeExtension>().baseVersionCode.get()
    set(value) { the<KeiyoushiThemeExtension>().baseVersionCode.set(value) }

internal var Project.libVersion: String
    get() = the<KeiyoushiThemeExtension>().libVersion.get()
    set(value) { the<KeiyoushiThemeExtension>().libVersion.set(value) }

internal fun Project.spotlessTaskName() = if (providers.environmentVariable("CI").orNull != "true") "spotlessApply" else "spotlessCheck"
