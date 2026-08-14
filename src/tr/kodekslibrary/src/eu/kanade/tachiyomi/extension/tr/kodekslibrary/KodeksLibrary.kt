package eu.kanade.tachiyomi.novelextension.tr.kodekslibrary

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class KodeksLibrary : LightNovelWPNovel() {
    override val reverseChapters = true
}
