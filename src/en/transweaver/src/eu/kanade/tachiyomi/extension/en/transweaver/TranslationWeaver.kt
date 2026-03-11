package eu.kanade.tachiyomi.extension.en.transweaver

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class TranslationWeaver :
    LightNovelWPNovel(
        baseUrl = "https://transweaver.com",
        name = "TranslationWeaver",
        lang = "en",
    ) {
    override val reverseChapters = true
}
