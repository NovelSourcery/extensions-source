package eu.kanade.tachiyomi.extension.en.requiemtranslations

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import eu.kanade.tachiyomi.source.model.Page

/**
 * Requiem Translations extension.
 * Uses LightNovelWP template with custom content decryption.
 */
class RequiemTranslations :
    LightNovelWPNovel(
        baseUrl = "https://requiemtls.com",
        name = "Requiem Translations",
        lang = "en",
    ) {

    /**
     * Decodes obfuscated text from Requiem Translations.
     * The site uses character offset encoding based on URL properties.
     * Based on LNReader implementation.
     */
    private fun decodeText(text: String, url: String): String {
        // Match LNReader logic exactly: url.slice(0, -1) which removes the last character
        val cleanUrl = url.dropLast(1)
        val offsets = listOf(
            listOf(0, 12368, 12462),
            listOf(1, 6960, 7054),
            listOf(2, 4176, 4270),
        )

        // Match LNReader: url.length * url.charCodeAt(url.length - 1) * 2 % 3
        val idx = (cleanUrl.length * cleanUrl.last().code * 2) % 3
        val offset = offsets.getOrElse(idx) { offsets[0] }
        val offsetLower = offset[1]
        val offsetCap = offset[2]

        val asciiA = 'A'.code
        val asciiz = 'z'.code

        return text.map { char ->
            val code = char.code
            val charOffset = if (code >= offsetLower + asciiA && code <= offsetLower + asciiz) {
                offsetLower
            } else {
                offsetCap
            }
            val decoded = code - charOffset
            if (decoded in 32..126) decoded.toChar() else char
        }.joinToString("")
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(
            okhttp3.Request.Builder()
                .url(baseUrl + page.url)
                .headers(headers)
                .build(),
        ).execute()

        val doc = response.asJsoup()

        val chapterPath = page.url.trimEnd('/')
        val decodeUrl = baseUrl + chapterPath

        doc.select("div.entry-content script").remove()
        doc.select(".unlock-buttons, .ads, style, .sharedaddy, .code-block").remove()

        val contentElement = doc.selectFirst("div.entry-content")
            ?: doc.selectFirst(".epcontent")
            ?: doc.selectFirst("#chapter-content")
            ?: return ""

        // Decode each direct child paragraph (like TS: $('div.entry-content > p'))
        contentElement.children().forEach { child ->
            if (child.tagName() == "p") {
                val originalText = child.text()
                val decodedText = decodeText(originalText, decodeUrl)
                child.text(decodedText)
            }
        }

        return contentElement.html()
    }
}
