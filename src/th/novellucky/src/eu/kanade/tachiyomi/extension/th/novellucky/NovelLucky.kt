package eu.kanade.tachiyomi.extension.th.novellucky

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class NovelLucky :
    MadaraNovel(
        baseUrl = "https://novel-lucky.com",
        name = "NovelLucky",
        lang = "th",
    ) {
    override val useNewChapterEndpointDefault = true
}
