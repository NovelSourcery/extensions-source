package eu.kanade.tachiyomi.extension.en.pasteltales

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class PastelTales :
    MadaraNovel(
        baseUrl = "https://pasteltales.com",
        name = "PastelTales",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
}
