package eu.kanade.tachiyomi.novelextension.fr.lightnovelfr

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class LightNovelFR : LightNovelWPNovel() {
    override val reverseChapters = true
}
