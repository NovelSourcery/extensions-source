package eu.kanade.tachiyomi.novelextension.en.dragonholic

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class Dragonholic : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
