package eu.kanade.tachiyomi.novelextension.ar.arnovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class ArNovel : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
