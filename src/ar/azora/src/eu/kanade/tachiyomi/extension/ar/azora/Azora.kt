package eu.kanade.tachiyomi.novelextension.ar.azora

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class Azora :
    MadaraNovel(
        baseUrl = "https://azoramoon.com",
        name = "Azora",
        lang = "ar",
    ) {
    override val useNewChapterEndpointDefault = true
}
