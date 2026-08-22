package eu.kanade.tachiyomi.novelextension.pt.centralnovel

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class CentralNovel : LightNovelWPNovel() {
    override val reverseChapters = true
}
