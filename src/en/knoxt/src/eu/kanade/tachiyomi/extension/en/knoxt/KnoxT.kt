package eu.kanade.tachiyomi.novelextension.en.knoxt

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class KnoxT : LightNovelWPNovel() {
    override val reverseChapters = false
}
