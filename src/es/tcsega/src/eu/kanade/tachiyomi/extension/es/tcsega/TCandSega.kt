package eu.kanade.tachiyomi.novelextension.es.tcsega

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class TCandSega : LightNovelWPNovel() {
    override val reverseChapters = true
}
