package eu.kanade.tachiyomi.novelextension.en.transweaver

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class TranslationWeaver : LightNovelWPNovel() {
    override val reverseChapters = true
}
