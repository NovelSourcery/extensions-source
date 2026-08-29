package eu.kanade.tachiyomi.novelextension.en.duskblossoms

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class DuskBlossoms : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
