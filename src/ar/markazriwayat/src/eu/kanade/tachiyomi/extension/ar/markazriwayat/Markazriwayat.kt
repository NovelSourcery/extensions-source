package eu.kanade.tachiyomi.extension.ar.markazriwayat

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class Markazriwayat :
    MadaraNovel(
        baseUrl = "https://markazriwayat.com",
        name = "Markazriwayat",
        lang = "ar",
    ) {
    override val useNewChapterEndpointDefault = true
}
