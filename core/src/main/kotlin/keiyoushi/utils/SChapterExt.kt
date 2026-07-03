package keiyoushi.utils

import eu.kanade.tachiyomi.source.model.SChapter

/**
 * Safely set chapter number/volume on an SChapter instance.
 * Uses reflection to remain compatible with any version of extensions-lib.
 * On Tsundoku (which adds chapter number/volume to SChapter), this sets the property directly.
 * On other apps without the property, this silently does nothing.
 */

@Suppress("UNCHECKED_CAST")
fun SChapter.setNumber(number: String?) {
    try {
        this::class.java.getMethod("setNumber", String::class.java)
            .invoke(this, number)
    } catch (_: Exception) {
        // number property not available on this version of extensions-lib
    }
}

@Suppress("UNCHECKED_CAST")
fun SChapter.setVolume(number: String?) {
    try {
        this::class.java.getMethod("setVolume", String::class.java)
            .invoke(this, number)
    } catch (_: Exception) {
        // volume property not available on this version of extensions-lib
    }
}
