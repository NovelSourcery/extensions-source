package eu.kanade.tachiyomi.extension.en.lightnovelheaven

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class LightNovelHeaven :
    MadaraNovel(
        baseUrl = "https://lightnovelheaven.com",
        name = "LightNovelHeaven",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
}
