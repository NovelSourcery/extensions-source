package eu.kanade.tachiyomi.extension.en.citrusaurora

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class CitrusAurora :
    MadaraNovel(
        baseUrl = "https://citrusaurora.com",
        name = "CitrusAurora",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
}
