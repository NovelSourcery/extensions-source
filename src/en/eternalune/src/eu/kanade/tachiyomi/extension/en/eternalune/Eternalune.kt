package eu.kanade.tachiyomi.novelextension.en.eternalune

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class Eternalune : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
