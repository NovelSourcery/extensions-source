package eu.kanade.tachiyomi.extension.id.novelbookid

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class NovelBookID :
    MadaraNovel(
        baseUrl = "https://www.novelbook.id",
        name = "NovelBookID",
        lang = "id",
    ) {
    override val useNewChapterEndpointDefault = true
}
