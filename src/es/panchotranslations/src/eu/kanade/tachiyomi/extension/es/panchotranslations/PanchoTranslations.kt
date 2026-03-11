package eu.kanade.tachiyomi.extension.es.panchotranslations

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class PanchoTranslations :
    MadaraNovel(
        baseUrl = "https://panchonovels.online",
        name = "PanchoTranslations",
        lang = "es",
    ) {
    override val useNewChapterEndpointDefault = true
}
