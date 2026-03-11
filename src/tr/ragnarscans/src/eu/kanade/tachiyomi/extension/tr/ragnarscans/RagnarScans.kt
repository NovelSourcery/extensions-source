package eu.kanade.tachiyomi.extension.tr.ragnarscans

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class RagnarScans :
    MadaraNovel(
        baseUrl = "https://ragnarscans.com",
        name = "RagnarScans",
        lang = "tr",
    ) {
    override val useNewChapterEndpointDefault = true
}
