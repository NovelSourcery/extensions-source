package eu.kanade.tachiyomi.novelextension.en.lullobox

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class LulloBox : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
}
