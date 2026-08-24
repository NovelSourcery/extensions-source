package eu.kanade.tachiyomi.novelextension.en.dragonholic

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class Dragonholic :
    MadaraNovel(
        baseUrl = "https://dragonholictranslations.com/",
        name = "Dragonholic",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
}
