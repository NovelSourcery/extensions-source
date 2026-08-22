package eu.kanade.tachiyomi.novelextension.en.lightnovelheaven

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath

@Source
abstract class LightNovelHeaven : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
    override val mangaPathTemplate = SlugPath("/series/")
}
