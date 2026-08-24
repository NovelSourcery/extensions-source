package eu.kanade.tachiyomi.novelextension.id.novelbookid

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class NovelBookID :
    MadaraNovel(
        baseUrl = "https://www.novelbook.id",
        name = "NovelBookID",
        lang = "id",
    ) {
    override val useNewChapterEndpointDefault = true
}
