package eu.kanade.tachiyomi.novelextension.ar.azora

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class Azora : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
