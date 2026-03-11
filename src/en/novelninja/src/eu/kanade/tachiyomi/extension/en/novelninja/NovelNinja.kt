package eu.kanade.tachiyomi.extension.en.novelninja

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class NovelNinja :
    MadaraNovel(
        baseUrl = "https://novelninja.xyz",
        name = "NovelNinja",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
}
