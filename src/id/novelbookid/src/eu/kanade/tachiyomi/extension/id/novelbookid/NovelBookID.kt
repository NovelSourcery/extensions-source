package eu.kanade.tachiyomi.novelextension.id.novelbookid

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class NovelBookID : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
}
