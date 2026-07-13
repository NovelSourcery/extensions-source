package io.github.keiyoushi.gradle.internal

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Same as [io.github.keiyoushi.gradle.tasks.GenerateKeepRulesTask], except the entry class name
 * is configurable rather than hardcoded to "ExtensionGenerated" -- kei's own version can assume
 * that because every modern-DSL source is KSP-generated under that fixed name, but legacy-DSL
 * extensions (PluginExtensionLegacy) often have a hand-written entry class with an arbitrary
 * name, so the keep rule needs to name it explicitly.
 */
@CacheableTask
abstract class GenerateLegacyKeepRulesTask : DefaultTask() {

    @get:Input
    abstract val applicationId: Property<String>

    @get:Input
    abstract val className: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun action() {
        outputDir.get().file("extClass.keep").asFile.apply {
            parentFile.mkdirs()
            writeText("-keep class ${applicationId.get()}.${className.get()} { <init>(); }\n")
        }
    }
}
