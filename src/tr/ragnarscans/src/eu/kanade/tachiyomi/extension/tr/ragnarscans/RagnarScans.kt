package eu.kanade.tachiyomi.novelextension.tr.ragnarscans

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class RagnarScans : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
