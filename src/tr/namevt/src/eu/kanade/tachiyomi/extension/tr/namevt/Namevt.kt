package eu.kanade.tachiyomi.extension.tr.namevt

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class Namevt :
    LightNovelWPNovel(
        baseUrl = "https://namevt.com",
        name = "Namevt",
        lang = "tr",
    ) {
    override val reverseChapters = true
    override val seriesPath = "seri"
}
