package eu.kanade.tachiyomi.extension.en.dragontea

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class DragonTea :
    MadaraNovel(
        baseUrl = "https://dragontea.ink",
        name = "DragonTea",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
}
