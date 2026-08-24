package eu.kanade.tachiyomi.novelextension.en.hireaththranslation

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class HiraethTranslation :
    MadaraNovel(
        baseUrl = "https://hiraethtranslation.com",
        name = "Hiraeth Translation",
        lang = "en",
    ) {
    // Uses new chapter endpoint per LN Reader plugin and instructions.txt
    override val useNewChapterEndpointDefault = true
}
