package eu.kanade.tachiyomi.novelextension.en.pasteltales

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class PastelTales :
    MadaraNovel(
        baseUrl = "https://pasteltales.com",
        name = "PastelTales",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
}
