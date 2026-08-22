package eu.kanade.tachiyomi.novelextension.en.hireaththranslation

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class HiraethTranslation : MadaraNovel() {
    // Uses new chapter endpoint per LN Reader plugin and instructions.txt
    override val useNewChapterEndpointDefault = true
}
