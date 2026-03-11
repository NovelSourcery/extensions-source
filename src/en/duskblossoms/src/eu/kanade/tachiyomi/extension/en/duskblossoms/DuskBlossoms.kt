package eu.kanade.tachiyomi.extension.en.duskblossoms

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class DuskBlossoms :
    MadaraNovel(
        baseUrl = "https://duskblossoms.com",
        name = "DuskBlossoms",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
}
