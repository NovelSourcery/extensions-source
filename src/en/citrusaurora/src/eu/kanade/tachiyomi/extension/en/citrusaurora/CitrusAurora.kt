package eu.kanade.tachiyomi.novelextension.en.citrusaurora

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath

@Source
abstract class CitrusAurora : MadaraNovel() {
    override val useNewChapterEndpointDefault = true
    override val mangaPathTemplate = SlugPath("/series/")
}
