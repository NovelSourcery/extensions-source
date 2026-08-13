package eu.kanade.tachiyomi.novelextension.en.dragontea

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class DragonTea : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
