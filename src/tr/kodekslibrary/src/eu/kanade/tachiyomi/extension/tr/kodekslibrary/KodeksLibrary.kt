package eu.kanade.tachiyomi.extension.tr.kodekslibrary

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class KodeksLibrary :
    LightNovelWPNovel(
        baseUrl = "https://www.kodekslibrary.com",
        name = "KodeksLibrary",
        lang = "tr",
    ) {
    override val reverseChapters = true
}
