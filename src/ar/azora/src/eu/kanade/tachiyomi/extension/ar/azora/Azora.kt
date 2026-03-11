package eu.kanade.tachiyomi.extension.ar.azora

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class Azora :
    MadaraNovel(
        baseUrl = "https://azoramoon.com",
        name = "Azora",
        lang = "ar",
    ) {
    override val useNewChapterEndpointDefault = true
}
