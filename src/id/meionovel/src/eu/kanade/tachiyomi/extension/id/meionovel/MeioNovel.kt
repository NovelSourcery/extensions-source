package eu.kanade.tachiyomi.novelextension.id.meionovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class MeioNovel : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
