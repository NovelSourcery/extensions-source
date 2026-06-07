package eu.kanade.tachiyomi.novelextension.en.webnoveltranslation

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class WebNovelTranslation :
    MadaraNovel(
        baseUrl = "https://webnoveltranslations.com",
        name = "WebNovel Translation",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
}
