package keiyoushi.utils

private val chapterPrefixRegex =
    Regex(
        """^\s*(?:chapter|chap|ch|episode|ep|vol(?:ume)?)\.?\s*\d+(?:\.\d+)?\s*[:,\-–—.)]*\s*""",
        RegexOption.IGNORE_CASE,
    )

/**
 * Strip a leading chapter/volume-number prefix from a chapter name so the title alone remains.
 * Examples: "Chapter 12 - Foo" -> "Foo", "12. Foo" -> "Foo", "Ch 3: Bar" -> "Bar".
 * Falls back to the trimmed original when nothing meaningful is left.
 */
fun String.stripChapterNumberPrefix(): String {
    val cleaned = chapterPrefixRegex.replaceFirst(this, "").trim()
    return cleaned.ifBlank { trim() }
}
