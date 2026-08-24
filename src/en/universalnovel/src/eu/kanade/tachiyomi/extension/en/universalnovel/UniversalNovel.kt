package eu.kanade.tachiyomi.novelextension.en.universalnovel

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import keiyoushi.annotation.Source

@Source
abstract class UniversalNovel : LightNovelWPNovel() {
    // Unlike most LightNovelWP sites, this instance lists chapters oldest-first in the DOM.
    override val reverseChapters = true
}
