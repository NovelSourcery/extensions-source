package eu.kanade.tachiyomi.novelextension.en.r18novel

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath

@Source
abstract class R18Novel : ReadNovelFull() {
    override val mangaPathTemplate = SlugPath("/webnovel/", "/")

    override val popularPage = "allvisit"
    override val latestPage = "dayvisit"

    override val noAjax = true
}
