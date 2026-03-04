package eu.kanade.tachiyomi.extension.en.foxaholic18

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class Foxaholic18 :
    MadaraNovel(
        baseUrl = "https://18.foxaholic.com",
        name = "Foxaholic 18+",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
}
