package eu.kanade.tachiyomi.novelextension.fr.massnovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class MassNovel :
    MadaraNovel(
        baseUrl = "https://massnovel.fr",
        name = "MassNovel",
        lang = "fr",
    ) {
    override val useNewChapterEndpointDefault = true
}
