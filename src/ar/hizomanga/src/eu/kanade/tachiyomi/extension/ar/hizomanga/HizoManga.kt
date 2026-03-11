package eu.kanade.tachiyomi.extension.ar.hizomanga

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class HizoManga :
    MadaraNovel(
        baseUrl = "https://hizomanga.net",
        name = "HizoManga",
        lang = "ar",
    ) {
    override val useNewChapterEndpointDefault = true
}
