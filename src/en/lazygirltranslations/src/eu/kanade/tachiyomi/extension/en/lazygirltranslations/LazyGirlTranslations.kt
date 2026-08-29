package eu.kanade.tachiyomi.novelextension.en.lazygirltranslations

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class LazyGirlTranslations : LightNovelWPNovel() {
    override val reverseChapters = true
}
