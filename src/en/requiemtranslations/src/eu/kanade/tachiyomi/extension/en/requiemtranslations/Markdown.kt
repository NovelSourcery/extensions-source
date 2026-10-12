package eu.kanade.tachiyomi.novelextension.en.requiemtranslations

private val blockSeparatorRegex = Regex("""\n\s*\n""")
private val headingRegex = Regex("""^#{1,6}\s*""")
private val ruleRegex = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
private val allowedTagRegex = Regex("""&lt;(/?)(u|b|i|em|strong|s|br)&gt;""", RegexOption.IGNORE_CASE)
private val boldRegex = Regex("""\*\*(.+?)\*\*""")
private val italicRegex = Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])""")
private val escapedCharRegex = Regex("""\\([\\*_#\[\]-])""")

// Chapter content is stored as Markdown (headings, rules, bold/italic and the odd inline HTML tag).
fun String.markdownToHtml(): String = replace("\r", "")
    .split(blockSeparatorRegex)
    .map(String::trim)
    .filter(String::isNotEmpty)
    .joinToString("\n") { block ->
        when {
            ruleRegex.matches(block) -> "<hr>"
            block.startsWith("#") && '\n' !in block -> "<h3>${block.replace(headingRegex, "").inline()}</h3>"
            else -> "<p>${block.lines().joinToString("<br>") { it.trim().inline() }}</p>"
        }
    }

private fun String.inline(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace(allowedTagRegex, "<$1$2>")
    .replace(boldRegex, "<b>$1</b>")
    .replace(italicRegex, "<i>$1</i>")
    .replace(escapedCharRegex, "$1")
