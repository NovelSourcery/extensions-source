package keiyoushi.utils

import org.jsoup.nodes.Element

/**
 * Returns the element's text with block boundaries and `<br>` preserved as
 * newlines, unlike [Element.text] which collapses them to single spaces.
 *
 * Use for descriptions/synopses where paragraph breaks matter.
 */
fun Element.formattedText(): String {
    val breakToken = "__KEIYO_BR__"
    val paragraphToken = "__KEIYO_P__"
    val node = clone()
    node.select("script, style").remove()
    node.select("br").forEach { it.after(breakToken) }
    node.select("p, div, h1, h2, h3, h4, h5, h6, li")
        // the root element itself can match but has no parent on the clone,
        // so after() would throw
        .filter { it !== node }
        .forEach { it.after(paragraphToken) }
    return node.text()
        .replace(Regex("\\u00A0"), " ")
        .replace(Regex("""\s*$paragraphToken\s*"""), "\n\n")
        .replace(Regex("""\s*$breakToken\s*"""), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
