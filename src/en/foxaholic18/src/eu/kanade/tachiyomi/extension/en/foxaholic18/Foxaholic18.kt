package eu.kanade.tachiyomi.novelextension.en.foxaholic18

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class Foxaholic18 : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
