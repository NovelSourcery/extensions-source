package io.github.keiyoushi.gradle.internal

import io.github.keiyoushi.gradle.api.dsl.ExtensionDeeplink
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.kotlin.dsl.newInstance

internal fun addDeeplink(
    objects: ObjectFactory,
    deeplinks: ListProperty<ExtensionDeeplink>,
    block: ExtensionDeeplink.() -> Unit,
) {
    deeplinks.add(objects.newInstance<ExtensionDeeplink>().apply(block))
}

internal fun computeSourceId(name: String, lang: String, versionId: Int = 1): Long {
    val key = "${name.lowercase()}/$lang/$versionId"
    val bytes = java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray())
    return (0..7).map { bytes[it].toLong() and 0xff }
        .reduce { acc, l -> (acc shl 8) or l } and Long.MAX_VALUE
}
