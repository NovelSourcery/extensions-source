package eu.kanade.tachiyomi.extension.en.knoxt

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class KnoxT :
    LightNovelWPNovel(
        baseUrl = "https://knoxt.space",
        name = "KnoxT",
        lang = "en",
    ) {
    override val reverseChapters = false
}
