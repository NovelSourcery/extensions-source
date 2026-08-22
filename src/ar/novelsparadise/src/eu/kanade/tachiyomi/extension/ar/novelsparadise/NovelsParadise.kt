package eu.kanade.tachiyomi.novelextension.ar.novelsparadise

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class NovelsParadise : LightNovelWPNovel() {
    override val reverseChapters = true
}
