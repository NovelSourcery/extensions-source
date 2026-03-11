package eu.kanade.tachiyomi.extension.en.etudetranslations

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class EtudeTranslations :
    MadaraNovel(
        baseUrl = "https://etudetranslations.com",
        name = "EtudeTranslations",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
}
