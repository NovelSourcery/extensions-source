package eu.kanade.tachiyomi.extension.id.sektenovel

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class SekteNovel :
    LightNovelWPNovel(
        baseUrl = "https://sektenovel.web.id",
        name = "SekteNovel",
        lang = "id",
    ) {
    override val reverseChapters = true
}
