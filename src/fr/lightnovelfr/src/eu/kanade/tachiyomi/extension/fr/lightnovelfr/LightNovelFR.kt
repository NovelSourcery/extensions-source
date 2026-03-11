package eu.kanade.tachiyomi.extension.fr.lightnovelfr

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class LightNovelFR :
    LightNovelWPNovel(
        baseUrl = "https://lightnovelfr.com",
        name = "LightNovelFR",
        lang = "fr",
    ) {
    override val reverseChapters = true
}
