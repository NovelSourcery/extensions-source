package eu.kanade.tachiyomi.novelextension.tr.webnoveloku

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath

@Source
abstract class WebNovelOku : MadaraNovel() {
    override val mangaPathTemplate = SlugPath("/manga/")
}
