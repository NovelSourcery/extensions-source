package eu.kanade.tachiyomi.novelextension.en.novelninja

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class NovelNinja : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
