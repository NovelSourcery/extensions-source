package eu.kanade.tachiyomi.novelextension.en.sleepytranslations

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath

@Source
abstract class SleepyTranslations : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
    override val mangaPathTemplate = SlugPath("/series/")
}
