package eu.kanade.tachiyomi.novelextension.en.lightnovelheaven

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source

@Source
abstract class LightNovelHeaven : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
}
