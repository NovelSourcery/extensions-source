package eu.kanade.tachiyomi.extension.en.lullobox

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class LulloBox :
    MadaraNovel(
        baseUrl = "https://lullobox.com",
        name = "LulloBox",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
}
