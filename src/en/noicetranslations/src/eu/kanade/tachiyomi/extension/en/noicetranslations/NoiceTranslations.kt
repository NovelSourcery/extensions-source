package eu.kanade.tachiyomi.novelextension.en.noicetranslations

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath

@Source
abstract class NoiceTranslations : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true
    override val mangaPathTemplate = SlugPath("/manga/")
}
