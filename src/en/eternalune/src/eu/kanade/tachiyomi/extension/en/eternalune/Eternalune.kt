package eu.kanade.tachiyomi.extension.en.eternalune

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class Eternalune :
    MadaraNovel(
        baseUrl = "https://eternalune.com",
        name = "Eternalune",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
}
