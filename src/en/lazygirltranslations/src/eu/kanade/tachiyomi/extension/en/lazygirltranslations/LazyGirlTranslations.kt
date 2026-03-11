package eu.kanade.tachiyomi.extension.en.lazygirltranslations

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class LazyGirlTranslations :
    LightNovelWPNovel(
        baseUrl = "https://lazygirltranslations.com",
        name = "LazyGirlTranslations",
        lang = "en",
    ) {
    override val reverseChapters = true
}
