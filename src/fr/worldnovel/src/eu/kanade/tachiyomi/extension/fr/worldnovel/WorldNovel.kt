package eu.kanade.tachiyomi.novelextension.fr.worldnovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class WorldNovel :
    MadaraNovel(
        baseUrl = "https://world-novel.fr",
        name = "WorldNovel",
        lang = "fr",
    ) {
    override val useNewChapterEndpointDefault = true
}
