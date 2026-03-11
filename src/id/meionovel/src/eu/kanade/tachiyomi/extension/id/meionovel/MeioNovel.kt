package eu.kanade.tachiyomi.extension.id.meionovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class MeioNovel :
    MadaraNovel(
        baseUrl = "https://meionovels.com",
        name = "MeioNovel",
        lang = "id",
    ) {
    override val useNewChapterEndpointDefault = true
}
