package eu.kanade.tachiyomi.extension.en.novelib

import eu.kanade.tachiyomi.multisrc.fictioneer.Fictioneer

class NovelLib :
    Fictioneer(
        name = "NovelLib",
        baseUrl = "https://novelib.com",
        lang = "en",
    ) {
    override val browsePage = "browse"
}
