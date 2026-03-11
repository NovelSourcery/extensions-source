package eu.kanade.tachiyomi.extension.id.bacalightnovel

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel

class BacaLightNovel :
    LightNovelWPNovel(
        baseUrl = "https://bacalightnovel.co",
        name = "BacaLightNovel",
        lang = "id",
    ) {
    override val reverseChapters = true
}
