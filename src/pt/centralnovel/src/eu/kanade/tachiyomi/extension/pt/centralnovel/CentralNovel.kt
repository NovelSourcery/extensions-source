package eu.kanade.tachiyomi.extension.pt.centralnovel

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class CentralNovel :
    LightNovelWPNovel(
        baseUrl = "https://centralnovel.com",
        name = "CentralNovel",
        lang = "pt",
    ) {
    override val reverseChapters = true
}
