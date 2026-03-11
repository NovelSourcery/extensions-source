package eu.kanade.tachiyomi.extension.en.noicetranslations

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class NoiceTranslations :
    MadaraNovel(
        baseUrl = "https://noicetranslations.com",
        name = "NoiceTranslations",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
}
