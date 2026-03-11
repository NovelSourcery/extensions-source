package eu.kanade.tachiyomi.extension.ko.fortuneeternal

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class FortuneEternal :
    MadaraNovel(
        baseUrl = "https://www.fortuneeternal.com",
        name = "FortuneEternal",
        lang = "ko",
    ) {
    override val useNewChapterEndpointDefault = true
}
