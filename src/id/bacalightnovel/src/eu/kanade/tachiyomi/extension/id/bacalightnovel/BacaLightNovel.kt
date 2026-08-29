package eu.kanade.tachiyomi.novelextension.id.bacalightnovel

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class BacaLightNovel : LightNovelWPNovel() {
    override val reverseChapters = true
}
