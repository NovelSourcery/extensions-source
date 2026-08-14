package eu.kanade.tachiyomi.novelextension.id.sektenovel

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class SekteNovel : LightNovelWPNovel() {
    override val reverseChapters = true
}
