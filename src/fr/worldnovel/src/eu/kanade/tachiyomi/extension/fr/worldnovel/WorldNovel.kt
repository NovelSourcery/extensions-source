package eu.kanade.tachiyomi.novelextension.fr.worldnovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class WorldNovel : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
