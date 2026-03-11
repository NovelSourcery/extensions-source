package eu.kanade.tachiyomi.extension.ar.novelsparadise

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class NovelsParadise :
    LightNovelWPNovel(
        baseUrl = "https://novelsparadise.site",
        name = "NovelsParadise",
        lang = "ar",
    ) {
    override val reverseChapters = true
}
