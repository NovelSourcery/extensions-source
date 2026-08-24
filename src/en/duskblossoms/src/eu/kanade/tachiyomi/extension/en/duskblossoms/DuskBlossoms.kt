package eu.kanade.tachiyomi.novelextension.en.duskblossoms

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class DuskBlossoms :
    MadaraNovel(
        baseUrl = "https://duskblossoms.com",
        name = "DuskBlossoms",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
}
