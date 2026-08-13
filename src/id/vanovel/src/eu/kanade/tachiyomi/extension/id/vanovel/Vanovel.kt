package eu.kanade.tachiyomi.novelextension.id.vanovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class Vanovel : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
