package eu.kanade.tachiyomi.novelextension.fr.massnovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class MassNovel : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
