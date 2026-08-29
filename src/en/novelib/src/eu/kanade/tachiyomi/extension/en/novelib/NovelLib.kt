package eu.kanade.tachiyomi.novelextension.en.novelib

import eu.kanade.tachiyomi.multisrc.fictioneer.Fictioneer
import keiyoushi.annotation.Source

@Source
abstract class NovelLib : Fictioneer() {
    override val browsePage = "browse"
}
