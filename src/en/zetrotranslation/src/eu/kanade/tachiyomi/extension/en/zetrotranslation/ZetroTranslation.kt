package eu.kanade.tachiyomi.extension.en.zetrotranslation

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel

class ZetroTranslation :
    MadaraNovel(
        baseUrl = "https://zetrotranslation.com",
        name = "Zetro Translation",
        lang = "en",
    ) {
    override val reverseChapterList = true
}
