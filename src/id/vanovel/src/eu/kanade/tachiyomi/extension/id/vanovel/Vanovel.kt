package eu.kanade.tachiyomi.extension.id.vanovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class Vanovel :
    MadaraNovel(
        baseUrl = "https://vanovel.com",
        name = "Vanovel",
        lang = "id",
    ) {
    override val useNewChapterEndpointDefault = true
}
