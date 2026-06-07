package eu.kanade.tachiyomi.novelextension.en.fanstranslations

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class FansTranslations :
    MadaraNovel(
        baseUrl = "https://fanstranslations.com",
        name = "Fans Translations",
        lang = "en",
    ) {
    // Uses new chapter endpoint per LN Reader plugin and instructions.txt
    override val useNewChapterEndpointDefault = true
}
