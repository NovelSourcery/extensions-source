package eu.kanade.tachiyomi.extension.es.tcsega

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class TCandSega :
    LightNovelWPNovel(
        baseUrl = "https://teamchmantranslations.com",
        name = "TCandSega",
        lang = "es",
    ) {
    override val reverseChapters = true
}
