package eu.kanade.tachiyomi.novelextension.ar.arnovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class ArNovel :
    MadaraNovel(
        baseUrl = "https://ar-no.com",
        name = "ArNovel",
        lang = "ar",
    ) {
    override val useNewChapterEndpointDefault = true
}
