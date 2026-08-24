package eu.kanade.tachiyomi.novelextension.tr.namevt

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class Namevt : LightNovelWPNovel() {
    override val reverseChapters = true
    override val seriesPath = "seri"
}
