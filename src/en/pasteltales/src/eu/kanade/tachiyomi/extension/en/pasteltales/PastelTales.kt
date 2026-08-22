package eu.kanade.tachiyomi.novelextension.en.pasteltales

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class PastelTales : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
